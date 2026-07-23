package ykd.ykd.wxbot;

import tools.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ykd.ykd.processor.MessageProcessor;
import ykd.ykd.processor.PerUserTaskDispatcher;
import ykd.ykd.processor.ProcessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink 智能机器人服务。
 *
 * <p>启动后通过二维码扫码登录微信，监听用户消息，将消息交由 {@link MessageProcessor} 处理，
 * 并将处理结果（文本/图片）发送给用户。支持 Session 持久化，重启后自动恢复登录。</p>
 */
@Slf4j
@Service
public class WeixinBotService {

    private static final Path SESSION_FILE = Paths.get("work", "ilink-session.json");
    private static final long RETRY_DELAY_MS = 2_000L;

    private final MessageProcessor messageProcessor;
    private final ObjectMapper objectMapper;
    private final PerUserTaskDispatcher dispatcher;

    private ILinkClient client;
    private volatile boolean running = true;
    private final AtomicBoolean pollingStarted = new AtomicBoolean(false);

    public WeixinBotService(MessageProcessor messageProcessor, ObjectMapper objectMapper) {
        this.messageProcessor = messageProcessor;
        this.objectMapper = objectMapper;
        this.dispatcher = new PerUserTaskDispatcher(8, 100, 5);
    }

    /**
     * 启动微信机器人。
     *
     * <p>优先尝试恢复保存的 Session，若无则生成 QR 码登录。
     */
        @PostConstruct
    public void start() {
            Thread loginThread = new Thread(()->{
                if ((hasSavedSession())){
                    log.info("检测到已保存的 Session，正在恢复微信登录...");
                }else {
                    log.info("未检测到Session，正在生成微信登录二维码");
                }
                login();
            }, "wx-bot-login");
            loginThread.setDaemon(true);
            loginThread.start();
    }

    /**
     * 优雅关闭：停止接收消息 → 排空队列 → 关闭客户端。
     */
    @PreDestroy
    public void stop() {
        running = false;
        dispatcher.close(Duration.ofSeconds(15));
        closeClient();
    }

    /**
     * 检查是否有保存的 Session。
     */
    public boolean hasSavedSession() {
        return Files.exists(SESSION_FILE);
    }

    /**
     * 删除保存的 Session 文件。
     */
    public void deleteSession() {
        try {
            Files.deleteIfExists(SESSION_FILE);
        } catch (IOException e) {
            log.warn("删除 session 文件失败: {}", e.getMessage());
        }
    }

    /**
     * 触发登录，返回 QR 码 URL（需扫码）或 null（Session 已恢复）。
     */
    public String login() {
        if (client != null) {
            closeClient();
        }

        ResumeContext resumeContext = loadSession();
        boolean needLogin = (resumeContext == null);

        client = ILinkClient.builder()
                .onLogin(new OnLoginListener() {
                    @Override
                    public void onLoginSuccess(LoginContext ctx) {
                        log.info("微信登录成功: botId={}", ctx.getBotId());
                        if (client != null) {
                            saveSession(client.exportResumeContext());
                        }
                        startPolling();
                    }

                    @Override
                    public void onLoginFailure(Throwable throwable) {
                        log.error("微信登录失败: {}", throwable.getMessage());
                    }
                })
                .build();

        if (resumeContext != null) {
            client = ILinkClient.builder()
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext ctx) {
                            log.info("Session 恢复成功: botId={}", ctx.getBotId());
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("Session 恢复失败: {}", throwable.getMessage());
                        }
                    })
                    .resumeContext(resumeContext)
                    .build();
            startPolling();
            return null;
        }

        try {
            String qrCodeContent = client.executeLogin();
            log.info("请扫码登录: {}", qrCodeContent);
            return qrCodeContent;
        } catch (Exception e) {
            log.error("获取 QR 码失败", e);
            return null;
        }
    }

    /**
     * 消息处理入口：通过 PerUserTaskDispatcher 提交任务，保证每用户串行执行。
     */
    private void handleMessage(WeixinMessage msg) {
        log.info("📩 [Bot] 收到微信消息: from={}, msgId={}", msg.getFrom_user_id(), msg.getMessage_id());
        String userId = msg.getFrom_user_id();

        boolean accepted = dispatcher.submit(userId, () -> {
            ProcessResult result = messageProcessor.process(msg, client);
            if (result == null) {
                return;
            }
            sendResult(result);
        });
        if (!accepted) {
            log.warn("任务队列已满，拒绝用户消息: userId={}", userId);
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

    private void safeSendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (Exception e) {
            log.error("[Bot] 发送文本失败: userId={}", userId, e);
        }
    }

    private void safeSendImage(String userId, byte[] imageData) {
        try {
            client.sendImage(userId, imageData, "image.png", "");
            log.info("[Bot] 图片发送成功: userId={}, size={}KB", userId, imageData.length / 1024);
        } catch (Exception e) {
            log.error("[Bot] 发送图片失败: userId={}", userId, e);
        }
    }

    private void safeSendVoice(String userId, byte[] voiceData) {
        try {
            client.sendFile(userId, voiceData, "voice.mp3", null);
            log.info("[Bot] 语音文件发送成功: userId={}, size={}KB", userId, voiceData.length / 1024);
        } catch (Exception e) {
            log.error("[Bot] 发送语音失败: userId={}", userId, e);
        }
    }

    private void safeSendVideo(String userId, byte[] videoData) {
        try {
            client.sendVideo(userId, videoData, "video.mp4", 0, "视频");
            log.info("[Bot] 视频发送成功: userId={}, size={}KB", userId, videoData.length / 1024);
        } catch (Exception e) {
            log.error("[Bot] 发送视频失败: userId={}", userId, e);
        }
    }

    /**
     * 检查并发送后台完成的视频。
     */
    private void sendCompletedReminder() {
        ProcessResult result = messageProcessor.pollCompletedReminder();
        while (result != null) {
            log.info("[Bot] 推送提醒: userId={}, text={}", result.userId(), result.text());
            safeSendText(result.userId(), result.text());
            result = messageProcessor.pollCompletedReminder();
        }
    }

    private void sendCompletedImageBatch() {
        ProcessResult result = messageProcessor.pollCompletedImageBatch();
        while (result != null) {
            log.info("[Bot] 推送图片批次结果: userId={}", result.userId());
            safeSendText(result.userId(), result.text());
            result = messageProcessor.pollCompletedImageBatch();
        }
    }

    private void sendCompletedVideo() {
        ProcessResult result = messageProcessor.pollCompletedVideo();
        while (result != null) {
            log.info("[Bot] 推送后台完成的视频: userId={}", result.userId());
            safeSendVideo(result.userId(), result.data());
            result = messageProcessor.pollCompletedVideo();
        }
    }

    // ===== Session 持久化 =====

    private void saveSession(ResumeContext resumeContext) {
        if (resumeContext == null || resumeContext.getLoginContext() == null) {
            return;
        }
        LoginContext login = resumeContext.getLoginContext();
        SessionData data = new SessionData(
                login.getBotToken(), login.getUserId(), login.getBotId(),
                login.getBaseUrl(), resumeContext.getUpdatesCursor());
        try {
            Files.createDirectories(SESSION_FILE.getParent());
            Files.writeString(SESSION_FILE, objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("保存 session 失败: {}", e.getMessage());
        }
    }

    /**
     * 从本地文件加载持久化的 iLink 会话。
     *
     * <p>文件不存在或内容无效时返回 {@code null}，由调用方回退到扫码登录。</p>
     *
     * @return 可恢复的 {@link ResumeContext}，无法恢复时返回 {@code null}
     */
    private ResumeContext loadSession() {
        if (!Files.exists(SESSION_FILE)) {
            return null;
        }
        try {
            String json = Files.readString(SESSION_FILE, StandardCharsets.UTF_8);
            SessionData data = objectMapper.readValue(json, SessionData.class);
            if (data.botToken() == null || data.botToken().isBlank()
                    || data.botId() == null || data.botId().isBlank()
                    || data.baseUrl() == null || data.baseUrl().isBlank()) {
                log.warn("Session 信息不完整，将重新登录");
                deleteSession();
                return null;
            }
            LoginContext loginContext = new LoginContext(
                    data.botToken(), data.userId(), data.botId(), data.baseUrl());
            return ResumeContext.builder(loginContext)
                    .updatesCursor(data.updatesCursor())
                    .build();
        } catch (Exception e) {
            log.warn("无法读取 iLink 会话，将重新登录: {}", e.getMessage());
            deleteSession();
            return null;
        }
    }

    private void startPolling() {
        if (!pollingStarted.compareAndSet(false, true)) {
            log.warn("消息轮询线程已经运行，跳过重复启动");
            return;
        }

        Thread pollThread = new Thread(() -> {
            log.info("消息轮询线程已启动 - 开始监听消息");

            try {
                while (running) {
                    try {
                        ILinkClient currentClient = client;

                        if (currentClient == null) {
                            log.warn("iLink 客户端为空，停止消息轮询");
                            break;
                        }

                        List<WeixinMessage> messages = currentClient.getUpdates();
                        saveSession(currentClient.exportResumeContext());

                        if (messages != null) {
                            for (WeixinMessage message : messages) {
                                handleMessage(message);
                            }
                        }

                        sendCompletedVideo();
                        sendCompletedReminder();
                        sendCompletedImageBatch();

                    } catch (SessionExpiredException e) {
                        log.warn("轮询异常-会话过期: {}", e.getMessage());
                        deleteSession();
                        break;
                    } catch (IOException e) {
                        log.warn("轮询异常-IO: {}", e.getMessage());
                        if (running) {
                            sleep(RETRY_DELAY_MS);
                        }
                    } catch (Exception e) {
                        log.error("消息轮询出现异常", e);
                        if (running) {
                            sleep(RETRY_DELAY_MS);
                        }
                    }
                }
            } finally {
                pollingStarted.set(false);
                log.info("消息轮询线程已停止");
            }
        }, "wx-poll");

        pollThread.setDaemon(true);
        pollThread.start();
    }
    private void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void closeClient() {
        ILinkClient current = client;
        client = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception e) {
                log.warn("关闭 iLink 客户端异常", e);
            }
        }
    }

    private record SessionData(
            String botToken, String userId, String botId,
            String baseUrl, String updatesCursor) {
    }
}
