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
            log.info("[BotService] 已断开用户 {} 的 session", botUserId);
        }
    }

    public void sendTextToUser(String userId, String text) {
        getAnySession().ifPresent(s -> s.safeSendText(userId, text));
    }

    public boolean sendTextWithResult(String userId, String text) {
        return getAnySession().map(s -> s.sendTextWithResult(userId, text)).orElse(false);
    }

    public boolean awaitReady(long timeoutSeconds) {
        return getAnySession().map(s -> s.awaitReady(timeoutSeconds)).orElse(false);
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
                    () -> onSessionReady(botUserId));
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
                () -> onSessionReady(botUserId));
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
