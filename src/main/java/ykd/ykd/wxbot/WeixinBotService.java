package ykd.ykd.wxbot;

import tools.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import ykd.ykd.processor.MessageProcessor;
import ykd.ykd.task.UnifiedReminderManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 微信 iLink 智能机器人服务（多用户版）。
 *
 * <p>管理多个 {@link BotSession} 实例，每个微信 bot 账号独立登录、独立轮询。
 * 启动时自动恢复所有已保存的 session，也支持通过 API 为新用户创建 session。</p>
 */
@Slf4j
@Service
public class WeixinBotService {

    private static final Path SESSION_DIR = Paths.get("work", "bot-sessions");

    private final ObjectMapper objectMapper;
    private final MessageProcessor messageProcessor;
    private final UnifiedReminderManager reminderManager;
    private final ApplicationEventPublisher eventPublisher;

    /** 已登录在线的 session：botUserId → BotSession */
    private final Map<String, BotSession> sessions = new ConcurrentHashMap<>();

    /** 等待扫码的 session：botUserId → BotSession（扫码成功后移到 sessions） */
    private final Map<String, BotSession> pendingSessions = new ConcurrentHashMap<>();

    /** 用户归属映射：微信 userId → botUserId，用于异步推送时路由到正确的 bot 账号 */
    private final Map<String, String> userToBot = new ConcurrentHashMap<>();

    /** 待扫码 session 超过该时长未登录则清理，避免永久驻留导致无法重新登录 */
    private static final long PENDING_SESSION_TTL_MS = 5 * 60 * 1000L;
    private ScheduledExecutorService pendingCleanupScheduler;

    public WeixinBotService(ObjectMapper objectMapper, MessageProcessor messageProcessor,
                            @Lazy UnifiedReminderManager reminderManager,
                            ApplicationEventPublisher eventPublisher) {
        this.objectMapper = objectMapper;
        this.messageProcessor = messageProcessor;
        this.reminderManager = reminderManager;
        this.eventPublisher = eventPublisher;
    }

    // ── 生命周期 ──────────────────────────────────────────────

    @PostConstruct
    public void start() {
        try { Files.createDirectories(SESSION_DIR); } catch (IOException e) {
            log.error("创建 session 目录失败", e);
        }
        migrateOldSession();

        // 定期清理长时间未扫码的 pending session
        pendingCleanupScheduler = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "wx-pending-cleanup");
            t.setDaemon(true);
            return t;
        });
        pendingCleanupScheduler.scheduleAtFixedRate(
                this::sweepStalePendingSessions, 60, 60, TimeUnit.SECONDS);

        Thread startupThread = new Thread(() -> {
            try (var dirStream = Files.list(SESSION_DIR)) {
                List<Path> sessionFiles = dirStream
                        .filter(p -> p.toString().endsWith(".json"))
                        .toList();
                log.info("[BotService] 发现 {} 个已保存的 session", sessionFiles.size());
                for (Path file : sessionFiles) {
                    String userId = file.getFileName().toString().replace(".json", "");
                    restoreSession(userId, file);
                }
            } catch (IOException e) {
                log.error("[BotService] 扫描 session 目录失败", e);
            }
        }, "wx-bot-startup");
        startupThread.setDaemon(true);
        startupThread.start();
    }

    @PreDestroy
    public void stop() {
        log.info("[BotService] 正在关闭所有 BotSession: online={}, pending={}",
                sessions.size(), pendingSessions.size());
        for (BotSession session : sessions.values()) {
            try { session.close(); } catch (Exception e) {
                log.warn("[BotService] 关闭 BotSession 异常: {}", e.getMessage());
            }
        }
        for (BotSession session : pendingSessions.values()) {
            try { session.close(); } catch (Exception e) {
                log.warn("[BotService] 关闭 pending BotSession 异常: {}", e.getMessage());
            }
        }
        sessions.clear();
        pendingSessions.clear();
        userToBot.clear();
        if (pendingCleanupScheduler != null) {
            pendingCleanupScheduler.shutdownNow();
        }
    }

    // ── 公共 API ──────────────────────────────────────────────

    /**
     * 为指定 botUserId 创建新 session 并登录，返回 QR 码 URL。
     * session 先放入 pendingSessions，扫码成功后自动移到 sessions。
     */
    public String login(String botUserId) {
        if (botUserId == null || botUserId.isBlank()) return null;
        if (sessions.containsKey(botUserId) || pendingSessions.containsKey(botUserId)) {
            log.info("[BotService] 用户 {} 已有活跃或等待中的 session，跳过", botUserId);
            return null;
        }
        return createAndStartSession(botUserId, sessionDir(botUserId));
    }

    public Optional<BotSession> getSession(String botUserId) {
        return Optional.ofNullable(sessions.get(botUserId));
    }

    public Optional<BotSession> getAnySession() {
        return sessions.values().stream().findFirst();
    }

    /** 已登录在线的 botUserId 列表 */
    public List<String> getActiveBotUsers() {
        return new ArrayList<>(sessions.keySet());
    }

    /** 等待扫码的 botUserId 列表 */
    public List<String> getPendingBotUsers() {
        return new ArrayList<>(pendingSessions.keySet());
    }

    public boolean isOnline(String botUserId) {
        return sessions.containsKey(botUserId);
    }

    public boolean hasAnyOnline() {
        return !sessions.isEmpty();
    }

    public void disconnect(String botUserId) {
        BotSession session = sessions.remove(botUserId);
        if (session == null) session = pendingSessions.remove(botUserId);
        if (session != null) {
            session.close();
            session.deleteSession();
            userToBot.entrySet().removeIf(e -> e.getValue().equals(botUserId));
            log.info("[BotService] 已断开用户 {} 的 session", botUserId);
        }
    }

    public void sendTextToUser(String userId, String text) {
        BotSession session = resolveSession(userId);
        if (session != null) {
            session.safeSendText(userId, text);
        }
    }

    public boolean sendTextWithResult(String userId, String text) {
        BotSession session = resolveSession(userId);
        return session != null && session.sendTextWithResult(userId, text);
    }

    public boolean awaitReady(long timeoutSeconds) {
        return getAnySession().map(s -> s.awaitReady(timeoutSeconds)).orElse(false);
    }

    /**
     * 记录用户与 bot 账号的归属关系，供异步推送（提醒等）选择正确的 bot。
     */
    public void registerUser(String botUserId, String userId) {
        if (userId == null || userId.isBlank()) return;
        userToBot.put(userId, botUserId);
    }

    /**
     * 根据用户 ID 找到其归属的在线 session；
     * 未记录归属时退回任意在线 session（兼容单 bot 场景）。
     */
    private BotSession resolveSession(String userId) {
        String botUserId = userToBot.get(userId);
        if (botUserId != null) {
            BotSession session = sessions.get(botUserId);
            if (session != null) return session;
        }
        return getAnySession().orElse(null);
    }

    /**
     * 清理超过 {@link #PENDING_SESSION_TTL_MS} 仍未扫码的 session，
     * 避免 pendingSessions 永久驻留导致该用户无法重新发起登录。
     */
    void sweepStalePendingSessions() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, BotSession> entry : pendingSessions.entrySet()) {
            BotSession session = entry.getValue();
            if (now - session.getCreatedAtMs() > PENDING_SESSION_TTL_MS
                    && pendingSessions.remove(entry.getKey(), session)) {
                try {
                    session.close();
                } catch (Exception e) {
                    log.warn("[BotService] 清理 pending session 关闭异常: {}", e.getMessage());
                }
                session.deleteSession();
                log.info("[BotService] 清理超时未扫码的 session: botUserId={}", entry.getKey());
            }
        }
    }

    // ── 内部方法 ──────────────────────────────────────────────

    /**
     * 扫码成功回调：从 pendingSessions 移到 sessions。
     */
    private void onSessionReady(String botUserId) {
        BotSession session = pendingSessions.remove(botUserId);
        if (session != null) {
            sessions.put(botUserId, session);
            log.info("[BotService] 用户 {} 扫码成功，已上线（当前在线: {}）", botUserId, sessions.size());
        }
        eventPublisher.publishEvent(new LoginReadyEvent(botUserId));
    }

    private void restoreSession(String botUserId, Path sessionFile) {
        try {
            BotSession session = new BotSession(botUserId, sessionFile, objectMapper,
                    messageProcessor, reminderManager,
                    () -> onSessionReady(botUserId),
                    userId -> registerUser(botUserId, userId));
            // 恢复的 session 直接放入 sessions（session 文件已存在，无需扫码）
            sessions.put(botUserId, session);
            String qr = session.login();
            if (qr == null) {
                log.info("[BotService] 已恢复用户 {} 的 session", botUserId);
            } else {
                // 恢复失败，需要重新扫码，移到 pending
                sessions.remove(botUserId);
                pendingSessions.put(botUserId, session);
                log.info("[BotService] 用户 {} session 恢复失败，等待重新扫码", botUserId);
            }
        } catch (Exception e) {
            log.error("[BotService] 恢复 session 失败: userId={}", botUserId, e);
        }
    }

    /**
     * 创建新 session，放入 pendingSessions，等待扫码。
     */
    private String createAndStartSession(String botUserId, Path sessionFile) {
        BotSession session = new BotSession(botUserId, sessionFile, objectMapper,
                messageProcessor, reminderManager,
                () -> onSessionReady(botUserId),
                userId -> registerUser(botUserId, userId));
        pendingSessions.put(botUserId, session);
        try {
            String qr = session.login();
            if (qr == null) {
                // session 文件已恢复成功，直接移到 online
                pendingSessions.remove(botUserId);
                sessions.put(botUserId, session);
            }
            return qr;
        } catch (Exception e) {
            pendingSessions.remove(botUserId);
            session.close();
            throw e;
        }
    }

    private void migrateOldSession() {
        Path oldFile = Paths.get("work", "ilink-session.json");
        if (!Files.exists(oldFile)) return;
        try {
            String json = Files.readString(oldFile);
            var node = objectMapper.readTree(json);
            String botUserId = node.has("userId") ? node.get("userId").asText() : null;
            if (botUserId == null || botUserId.isBlank()) {
                log.warn("[BotService] 旧 session 无 userId，跳过迁移");
                return;
            }
            Path newFile = sessionDir(botUserId);
            if (!Files.exists(newFile)) {
                Files.createDirectories(newFile.getParent());
                Files.move(oldFile, newFile);
                log.info("[BotService] 已迁移旧 session 到: {}", newFile);
            } else {
                Files.delete(oldFile);
                log.info("[BotService] 新 session 已存在，删除旧文件");
            }
        } catch (IOException e) {
            log.warn("[BotService] 旧 session 迁移失败: {}", e.getMessage());
        }
    }

    private static Path sessionDir(String botUserId) {
        return SESSION_DIR.resolve(botUserId + ".json");
    }
}
