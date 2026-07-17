package com.clitoolbox.ilink;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.weather.WeatherResult;
import com.clitoolbox.weather.WeatherService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.config.ILinkConfig;
import com.github.wechat.ilink.sdk.core.context.ResumeContext;
import com.github.wechat.ilink.sdk.core.exception.SessionExpiredException;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ILinkService {
    private static final Logger LOG = Logger.getLogger(ILinkService.class.getName());
    private static final Path WORK_DIR = Paths.get("work");
    private static final Path SESSION_FILE = WORK_DIR.resolve("ilink-session.json");
    private static final long RETRY_DELAY_MS = 2_000L;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private volatile ILinkClient client;

    public void login() {
        System.out.println("正在获取登录二维码...");
        closeClient();
        client = createClient(null);
        try {
            String qrCodeContent = client.executeLogin();
            System.out.println("========== iLink 登录 ==========");
            System.out.println("请用微信扫描以下二维码内容:");
            System.out.println(qrCodeContent);
            System.out.println("================================");
            System.out.println("等待扫码并在微信上确认...");

            LoginContext context = client.getLoginFuture().get();
            saveSession(client.exportResumeContext());
            System.out.println("登录成功! BotID=" + context.getBotId() + " (会话已保存)");
            System.out.println("现在开始监听消息...\n");
            listenWithCurrentClient();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeClient();
            throw new CliException(ErrorCode.NETWORK_ERROR, "登录被中断。");
        } catch (ExecutionException e) {
            closeClient();
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new CliException(ErrorCode.NETWORK_ERROR, "登录失败: " + cause.getMessage());
        } catch (CliException e) {
            closeClient();
            throw e;
        } catch (Exception e) {
            closeClient();
            throw new CliException(ErrorCode.UNKNOWN, "登录失败: " + e.getMessage());
        }
    }

    public void listen() {
        ResumeContext resumeContext = loadSession();
        if (resumeContext == null) {
            login();
            return;
        }
        closeClient();
        client = createClient(resumeContext);
        listenWithCurrentClient();
    }

    private void listenWithCurrentClient() {
        System.out.println("正在连接 iLink 消息服务...");
        ILinkClient activeClient = client;
        AtomicBoolean stop = new AtomicBoolean(false);
        Thread shutdownHook = new Thread(() -> {
            stop.set(true);
            System.out.println("\n断开连接...");
            closeClient();
        }, "ilink-shutdown");
        Runtime.getRuntime().addShutdownHook(shutdownHook);

        System.out.println("已连接！发送城市名即可查询天气 (按 Ctrl+C 停止)...\n");

        try {
            while (!stop.get()) {
                try {
                    List<WeixinMessage> messages = activeClient.getUpdates();
                    saveSession(activeClient.exportResumeContext());
                    for (WeixinMessage message : messages) {
                        handleMessage(activeClient, message);
                    }
                } catch (SessionExpiredException e) {
                    deleteSessionFile();
                    System.out.println("会话已过期，请执行 chat login 重新登录。");
                    break;
                } catch (IOException e) {
                    if (stop.get()) {
                        break;
                    }
                    LOG.warning("监听错误: " + e.getMessage());
                    sleepBeforeRetry(stop);
                }
            }
        } finally {
            stop.set(true);
            closeClient();
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM 已进入关闭阶段，无需移除钩子。
            }
        }
    }

    private void handleMessage(ILinkClient activeClient, WeixinMessage message) {
        String userId = message.getFrom_user_id();
        String text = extractText(message);
        if (userId == null || userId.isBlank() || text == null || text.isBlank()) {
            return;
        }

        System.out.println("[" + userId + "] " + text);
        try {
            activeClient.sendText(userId, processMessage(text));
            System.out.println("  -> 已回复");
        } catch (Exception e) {
            System.out.println("  -> 回复失败: " + e.getMessage());
        }
    }

    private String extractText(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (MessageItem item : message.getItem_list()) {
            if (item != null && item.getText_item() != null
                    && item.getText_item().getText() != null
                    && !item.getText_item().getText().isBlank()) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(item.getText_item().getText());
            }
        }
        return text.isEmpty() ? null : text.toString();
    }

    /** 智能消息处理: XX天气 → 查天气 | 纯城市名 → 查天气 | 其他 → 原样回复 */
    private String processMessage(String text) {
        String city = extractCity(text);
        if (city != null) return tryWeather(city, text);
        if (text.length() >= 2 && text.length() <= 6) {
            String r = tryWeather(text, null);
            if (r != null) return r;
        }
        return "已收到您的消息: " + text;
    }

    private String tryWeather(String city, String fallback) {
        try {
            WeatherResult r = new WeatherService().query(city);
            return formatWeather(r);
        } catch (Exception e) { return fallback; }
    }

    private String extractCity(String text) {
        for (String p : new String[]{"(.+?)天气.*","(.+?)气温.*","(.+?)多少度.*","(.+?)温度.*"}) {
            Matcher m = Pattern.compile(p).matcher(text);
            if (m.matches()) return m.group(1).trim();
        }
        return null;
    }

    private String formatWeather(WeatherResult r) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return "【" + r.city() + "实时天气】\n"
            + r.weatherDescription() + "，" + String.format("%.0f", r.temperature()) + "C"
            + " (体感 " + String.format("%.0f", r.feelsLike()) + "C)\n"
            + "今日 " + String.format("%.0f", r.lowTemp()) + "~" + String.format("%.0f", r.highTemp()) + "C\n" + "湿度 " + r.humidity() + "% | 风速 " + r.windSpeed() + " m/s\n"
            + "更新时间: " + sdf.format(new Date(r.updateTime()));
    }

    private ILinkClient createClient(ResumeContext resumeContext) {
        ILinkConfig config = ILinkConfig.builder()
                .heartbeatEnabled(false)
                .build();
        var builder = ILinkClient.builder().config(config);
        if (resumeContext != null) {
            builder.resumeContext(resumeContext);
        }
        return builder.build();
    }

    private void saveSession(ResumeContext resumeContext) {
        if (resumeContext == null || resumeContext.getLoginContext() == null) {
            return;
        }
        LoginContext login = resumeContext.getLoginContext();
        SessionData data = new SessionData(
                login.getBotToken(),
                login.getUserId(),
                login.getBotId(),
                login.getBaseUrl(),
                resumeContext.getUpdatesCursor());
        try {
            Files.createDirectories(WORK_DIR);
            Files.writeString(SESSION_FILE, OBJECT_MAPPER.writeValueAsString(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warning("无法保存 iLink 会话: " + e.getMessage());
        }
    }

    private ResumeContext loadSession() {
        if (!Files.exists(SESSION_FILE)) {
            return null;
        }
        try {
            SessionData data = OBJECT_MAPPER.readValue(
                    Files.readString(SESSION_FILE, StandardCharsets.UTF_8), SessionData.class);
            if (data.botToken() == null || data.botToken().isBlank()
                    || data.botId() == null || data.botId().isBlank()
                    || data.baseUrl() == null || data.baseUrl().isBlank()) {
                throw new IOException("会话信息不完整");
            }
            LoginContext login = new LoginContext(
                    data.botToken(), data.userId(), data.botId(), data.baseUrl());
            return ResumeContext.builder(login)
                    .updatesCursor(data.updatesCursor())
                    .build();
        } catch (Exception e) {
            LOG.warning("无法读取 iLink 会话，将重新登录: " + e.getMessage());
            deleteSessionFile();
            return null;
        }
    }

    private void deleteSessionFile() {
        try {
            Files.deleteIfExists(SESSION_FILE);
        } catch (IOException e) {
            LOG.warning("无法删除失效的 iLink 会话: " + e.getMessage());
        }
    }

    private void sleepBeforeRetry(AtomicBoolean stop) {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stop.set(true);
        }
    }

    private synchronized void closeClient() {
        ILinkClient current = client;
        client = null;
        if (current != null) {
            current.close();
        }
    }

    private record SessionData(
            String botToken,
            String userId,
            String botId,
            String baseUrl,
            String updatesCursor) {
    }
}
