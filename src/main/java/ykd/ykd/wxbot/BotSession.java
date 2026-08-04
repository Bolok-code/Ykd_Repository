package ykd.ykd.wxbot;

import tools.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import lombok.extern.slf4j.Slf4j;
import ykd.ykd.memory.MemoryManagerService;
import ykd.ykd.processor.MessageProcessor;
import ykd.ykd.processor.PerUserTaskDispatcher;
import ykd.ykd.processor.ProcessResult;
import ykd.ykd.task.UnifiedReminderManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 单个微信 bot 账号的会话。
 *
 * <p>封装了 ILinkClient、消息轮询线程、session 持久化、消息发送等全部逻辑，
 * 由 {@link WeixinBotService} 统一管理多个 BotSession 实例。</p>
 */
@Slf4j
public class BotSession implements AutoCloseable {

    private final String botUserId;
    private final Path sessionFile;
    private final ObjectMapper objectMapper;
    private final MessageProcessor messageProcessor;
    private final UnifiedReminderManager reminderManager;
    private final Runnable onReady;

    private ILinkClient client;
    private final PerUserTaskDispatcher dispatcher;
    private final CountDownLatch loginReady = new CountDownLatch(1);
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);
    private final Map<String, Long> lastSendTime = new ConcurrentHashMap<>();
    private static final long MIN_SEND_INTERVAL_MS = 2_000L;
    private static final long RETRY_DELAY_MS = 2_000L;

    /** 会话创建时间，用于 WeixinBotService 对待扫码会话做超时清理 */
    private final long createdAtMs = System.currentTimeMillis();
    /** 发送限速调度器：把待发送消息延迟到时间槽，不阻塞消息处理工作线程 */
    private final ScheduledExecutorService senderScheduler;
    /** 空轮询时的心跳持久化间隔，避免频繁写盘又不会丢失 updates cursor */
    private static final long SESSION_SAVE_HEARTBEAT_MS = 30_000L;
    /** 轮询连续失败阈值，达到后停止轮询，避免持久性错误无限重试 */
    private static final int MAX_CONSECUTIVE_ERRORS = 20;
    private int consecutiveErrors = 0;
    private long lastSessionSaveAt = 0L;

    private volatile boolean running = true;

    public BotSession(String botUserId, Path sessionFile, ObjectMapper objectMapper,
                      MessageProcessor messageProcessor, UnifiedReminderManager reminderManager,
                      Runnable onReady) {
        this.botUserId = botUserId;
        this.sessionFile = sessionFile;
        this.objectMapper = objectMapper;
        this.messageProcessor = messageProcessor;
        this.reminderManager = reminderManager;
        this.onReady = onReady;
        this.dispatcher = new PerUserTaskDispatcher(8, 100, 5);
        this.senderScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "wx-sender-" + botUserId);
            t.setDaemon(true);
            return t;
        });
    }

    public String getBotUserId() {
        return botUserId;
    }

    public long getCreatedAtMs() {
        return createdAtMs;
    }

    public boolean awaitReady(long timeoutSeconds) {
        try {
            return loginReady.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ── 登录 ──────────────────────────────────────────────────

    /**
     * 登录并返回 QR 码 URL，若 session 已恢复则返回 null。
     */
    public String login() {
        running = true;
        if (client != null) {
            closeClient();
        }

        ResumeContext resumeContext = loadSession();

        if (resumeContext != null) {
            client = ILinkClient.builder()
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext ctx) {
                            log.info("[BotSession:{}] Session 恢复成功: botId={}", botUserId, ctx.getBotId());
                        }
                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("[BotSession:{}] Session 恢复失败: {}", botUserId, throwable.getMessage());
                        }
                    })
                    .resumeContext(resumeContext)
                    .build();
            onSessionReady();
            return null;
        }

        client = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("[BotSession:{}] 微信登录成功: botId={}", botUserId, ctx.getBotId());
                        saveSession(client.exportResumeContext());
                        onSessionReady();
                    }
                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("[BotSession:{}] 微信登录失败: {}", botUserId, throwable.getMessage());
                    }
                })
                .build();

        try {
            String qrCodeContent = client.executeLogin();
            log.info("[BotSession:{}] 请扫码登录: {}", botUserId, qrCodeContent);
            return qrCodeContent;
        } catch (Exception e) {
            log.error("[BotSession:{}] 获取 QR 码失败", botUserId, e);
            return null;
        }
    }

    private void onSessionReady() {
        loginReady.countDown();
        startPolling();
        if (onReady != null) {
            onReady.run();
        }
    }

    // ── 消息轮询 ──────────────────────────────────────────────

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) {
            log.warn("[BotSession:{}] 消息轮询线程已经运行，跳过", botUserId);
            return;
        }

        Thread pollThread = new Thread(() -> {
            log.info("[BotSession:{}] 消息轮询线程已启动", botUserId);
            try {
                while (running) {
                    try {
                        ILinkClient currentClient = client;
                        if (currentClient == null) {
                            log.warn("[BotSession:{}] iLink 客户端为空，停止轮询", botUserId);
                            break;
                        }

                        List<WeixinMessage> messages = currentClient.getUpdates();
                        // 拿到新消息时才写 session；空轮询每 30 秒做一次心跳持久化，
                        // 既减少磁盘 IO，又避免长时间空轮询后丢失 updates cursor
                        if (messages != null && !messages.isEmpty()) {
                            saveSession(currentClient.exportResumeContext());
                            for (WeixinMessage message : messages) {
                                handleMessage(message);
                                reminderManager.resumeAllForUser(message.getFrom_user_id());
                            }
                        } else if (System.currentTimeMillis() - lastSessionSaveAt > SESSION_SAVE_HEARTBEAT_MS) {
                            saveSession(currentClient.exportResumeContext());
                        }

                        sendCompletedVideo();
                        sendCompletedImageBatch();
                        sendCompletedLiepinTask();

                        consecutiveErrors = 0;

                    } catch (SessionExpiredException e) {
                        log.warn("[BotSession:{}] 轮询异常-会话过期: {}", botUserId, e.getMessage());
                        deleteSession();
                        break;
                    } catch (IOException e) {
                        log.warn("[BotSession:{}] 轮询异常-IO: {}", botUserId, e.getMessage());
                        if (!handlePollError()) break;
                        if (running) sleep(RETRY_DELAY_MS);
                    } catch (Exception e) {
                        log.error("[BotSession:{}] 消息轮询异常", botUserId, e);
                        if (!handlePollError()) break;
                        if (running) sleep(RETRY_DELAY_MS);
                    }
                }
            } finally {
                pollingStarted.set(false);
                log.info("[BotSession:{}] 消息轮询线程已停止", botUserId);
            }
        }, "wx-poll-" + botUserId);
        pollThread.setDaemon(true);
        pollThread.start();
    }

    // ── 消息处理 ──────────────────────────────────────────────

    private void handleMessage(WeixinMessage msg) {
        log.info("📩 [BotSession:{}] 收到微信消息: from={}, msgId={}", botUserId, msg.getFrom_user_id(), msg.getMessage_id());
        String userId = msg.getFrom_user_id();

        boolean accepted = dispatcher.submit(userId, () -> {
            ProcessResult result = messageProcessor.process(msg, client);
            if (result == null) return;
            sendResult(result);
        });

        if (!accepted) {
            log.warn("[BotSession:{}] 任务队列已满，拒绝消息: userId={}", botUserId, userId);
            safeSendText(userId, "⏳ 当前消息过多，请稍后再试");
        }
    }

    private void sendResult(ProcessResult result) {
        switch (result.type()) {
            case IMAGE -> safeSendImage(result.userId(), result.data());
            case VIDEO -> safeSendVideo(result.userId(), result.data());
            case VOICE -> safeSendVoice(result.userId(), result.data());
            case TEXT -> safeSendText(result.userId(), result.text());
        }
    }

    // ── 发送方法 ──────────────────────────────────────────────

    /**
     * 发送文本，带同用户限速。
     *
     * <p>不阻塞调用线程：需要等待限速间隔时，把发送动作丢给调度器延迟执行，
     * 避免消息处理工作线程被 Thread.sleep 卡住。</p>
     */
    public void safeSendText(String userId, String text) {
        long now = System.currentTimeMillis();
        // 原子地预约下一条消息的发送时间槽，保证同用户消息间隔 >= MIN_SEND_INTERVAL_MS
        long sendAt = lastSendTime.compute(userId,
                (k, last) -> Math.max(now, last == null ? now : last + MIN_SEND_INTERVAL_MS));
        long delayMs = sendAt - now;
        if (delayMs > 0) {
            senderScheduler.schedule(() -> doSendText(userId, text), delayMs, TimeUnit.MILLISECONDS);
        } else {
            doSendText(userId, text);
        }
    }

    private void doSendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (Exception e) {
            log.error("[BotSession:{}] 发送文本失败: userId={}", botUserId, userId, e);
        }
    }

    public boolean sendTextWithResult(String userId, String text) {
        try {
            client.sendText(userId, text);
            lastSendTime.put(userId, System.currentTimeMillis());
            return true;
        } catch (Exception e) {
            if (e.getMessage() != null && e.getMessage().contains("prepare failed")) {
                log.warn("[BotSession:{}] 协议过期: userId={}", botUserId, userId);
            } else {
                log.error("[BotSession:{}] 发送文本失败: userId={}", botUserId, userId, e);
            }
            return false;
        }
    }

    private void safeSendImage(String userId, byte[] imageData) {
        try {
            client.sendImage(userId, imageData, "image.png", "");
        } catch (Exception e) {
            log.error("[BotSession:{}] 发送图片失败: userId={}", botUserId, userId, e);
        }
    }

    private void safeSendVoice(String userId, byte[] voiceData) {
        try {
            client.sendFile(userId, voiceData, "voice.mp3", null);
        } catch (Exception e) {
            log.error("[BotSession:{}] 发送语音失败: userId={}", botUserId, userId, e);
        }
    }

    private void safeSendVideo(String userId, byte[] videoData) {
        try {
            client.sendVideo(userId, videoData, "video.mp4", 0, "视频");
        } catch (Exception e) {
            log.error("[BotSession:{}] 发送视频失败: userId={}", botUserId, userId, e);
        }
    }

    // ── 后台结果推送 ──────────────────────────────────────────

    private void sendCompletedVideo() {
        ProcessResult result = messageProcessor.pollCompletedVideo();
        while (result != null) {
            safeSendVideo(result.userId(), result.data());
            result = messageProcessor.pollCompletedVideo();
        }
    }

    private void sendCompletedImageBatch() {
        ProcessResult result = messageProcessor.pollCompletedImageBatch();
        while (result != null) {
            safeSendText(result.userId(), result.text());
            result = messageProcessor.pollCompletedImageBatch();
        }
    }

    private void sendCompletedLiepinTask() {
        ProcessResult result = messageProcessor.pollCompletedLiepinTask();
        while (result != null) {
            safeSendText(result.userId(), result.text());
            result = messageProcessor.pollCompletedLiepinTask();
        }
    }

    // ── Session 持久化 ────────────────────────────────────────

    private record SessionData(String botToken, String userId, String botId,
                               String baseUrl, String updatesCursor) {}

    private void saveSession(ResumeContext resumeContext) {
        if (resumeContext == null || resumeContext.getLoginContext() == null) return;
        lastSessionSaveAt = System.currentTimeMillis();
        LoginContext login = resumeContext.getLoginContext();
        SessionData data = new SessionData(login.getBotToken(), login.getUserId(),
                login.getBotId(), login.getBaseUrl(), resumeContext.getUpdatesCursor());
        try {
            Files.createDirectories(sessionFile.getParent());
            Files.writeString(sessionFile, objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[BotSession:{}] 保存 session 失败: {}", botUserId, e.getMessage());
        }
    }

    private ResumeContext loadSession() {
        if (!Files.exists(sessionFile)) return null;
        try {
            String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
            SessionData data = objectMapper.readValue(json, SessionData.class);
            if (data.botToken() == null || data.botToken().isBlank()
                    || data.botId() == null || data.botId().isBlank()
                    || data.baseUrl() == null || data.baseUrl().isBlank()) {
                log.warn("[BotSession:{}] Session 信息不完整，将重新登录", botUserId);
                deleteSession();
                return null;
            }
            LoginContext loginContext = new LoginContext(data.botToken(), data.userId(), data.botId(), data.baseUrl());
            return ResumeContext.builder(loginContext).updatesCursor(data.updatesCursor()).build();
        } catch (Exception e) {
            log.warn("[BotSession:{}] 无法读取 session: {}", botUserId, e.getMessage());
            deleteSession();
            return null;
        }
    }

    public void deleteSession() {
        try { Files.deleteIfExists(sessionFile); } catch (IOException e) {
            log.warn("[BotSession:{}] 删除 session 失败: {}", botUserId, e.getMessage());
        }
    }

    // ── 关闭 ──────────────────────────────────────────────────

    public void closeClient() {
        ILinkClient current = client;
        client = null;
        if (current != null) {
            try { current.close(); } catch (Exception e) {
                log.warn("[BotSession:{}] 关闭 iLink 客户端异常", botUserId, e);
            }
        }
    }

    @Override
    public void close() {
        running = false;
        senderScheduler.shutdownNow();
        dispatcher.close(Duration.ofSeconds(15));
        closeClient();
    }

    /**
     * 累计轮询连续失败次数，超过阈值返回 false 以停止轮询。
     *
     * @return true=继续重试；false=应停止轮询
     */
    private boolean handlePollError() {
        consecutiveErrors++;
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            log.error("[BotSession:{}] 轮询连续失败 {} 次，停止轮询", botUserId, MAX_CONSECUTIVE_ERRORS);
            return false;
        }
        return true;
    }

    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
