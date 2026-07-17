package com.clitoolbox.ilink.service.impl;

import com.clitoolbox.ai.image.GeneratedImage;
import com.clitoolbox.ai.image.ImageGenerationClient;
import com.clitoolbox.ai.vision.ImageUnderstandingClient;
import com.clitoolbox.conversation.ChatService;
import com.clitoolbox.conversation.PerUserTaskDispatcher;
import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.ilink.router.ILinkMessageRouter;
import com.clitoolbox.ilink.router.ILinkMessageRouter.MessageRoute;
import com.clitoolbox.ilink.service.ILinkService;
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
import jakarta.annotation.PreDestroy;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public class ILinkServiceImpl implements ILinkService {
    private static final Logger LOG = LoggerFactory.getLogger(ILinkServiceImpl.class);
    private static final Path WORK_DIR = Paths.get("work");
    private static final Path SESSION_FILE = WORK_DIR.resolve("ilink-session.json");
    private static final long RETRY_DELAY_MS = 2_000L;
    private final PerUserTaskDispatcher chatDispatcher;
    private final ObjectMapper objectMapper;
    private final WeatherService weatherService;
    private final ObjectProvider<ChatService> chatServiceProvider;
    private final ObjectProvider<ImageUnderstandingClient> imageUnderstandingClientProvider;
    private final ObjectProvider<ImageGenerationClient> imageGenerationClientProvider;
    private final ILinkMessageRouter messageRouter;
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    private volatile ILinkClient client;

    public ILinkServiceImpl(
            PerUserTaskDispatcher chatDispatcher,
            ObjectMapper objectMapper,
            WeatherService weatherService,
            ObjectProvider<ChatService> chatServiceProvider,
            ObjectProvider<ImageUnderstandingClient> imageUnderstandingClientProvider,
            ObjectProvider<ImageGenerationClient> imageGenerationClientProvider,
            ILinkMessageRouter messageRouter) {
        this.chatDispatcher = chatDispatcher;
        this.objectMapper = objectMapper;
        this.weatherService = weatherService;
        this.chatServiceProvider = chatServiceProvider;
        this.imageUnderstandingClientProvider = imageUnderstandingClientProvider;
        this.imageGenerationClientProvider = imageGenerationClientProvider;
        this.messageRouter = messageRouter;
    }

    @Override
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

    @Override
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
        stopping.set(false);
        ILinkClient activeClient = client;

        System.out.println(
                "已连接！支持文字聊天、天气查询、发送图片识别问题，以及自然语言文生图"
                        + " (按 Ctrl+C 停止)...\n");

        try {
            while (!stopping.get()) {
                try {
                    List<WeixinMessage> messages = activeClient.getUpdates();
                    saveSession(activeClient.exportResumeContext());
                    if (messages != null) {
                        for (WeixinMessage message : messages) {
                            dispatchMessage(activeClient, message);
                        }
                    }
                } catch (SessionExpiredException e) {
                    deleteSessionFile();
                    System.out.println("会话已过期，请执行 chat login 重新登录。");
                    break;
                } catch (IOException e) {
                    if (stopping.get()) {
                        break;
                    }
                    LOG.warn("监听错误: {}", e.getMessage());
                    sleepBeforeRetry();
                }
            }
        } finally {
            stop();
        }
    }

    private void dispatchMessage(ILinkClient activeClient, WeixinMessage message) {
        if (stopping.get()) {
            return;
        }
        String userId = message.getFrom_user_id();
        String text = extractText(message);
        MessageItem imageItem = firstImageItem(message);
        if (userId == null || userId.isBlank()
                || ((text == null || text.isBlank()) && imageItem == null)) {
            return;
        }

        String messageSummary = imageItem == null
                ? text
                : (text == null || text.isBlank() ? "[图片]" : "[图片] " + text);
        System.out.println("[" + userId + "] " + messageSummary);
        boolean accepted = chatDispatcher.submit(
                userId,
                () -> replyToMessage(activeClient, userId, text, imageItem));
        if (!accepted) {
            LOG.warn("聊天任务队列已满，拒绝用户消息: {}", userId);
        }
    }

    private void replyToMessage(
            ILinkClient activeClient,
            String userId,
            String text,
            MessageItem imageItem) {
        try {
            if (stopping.get()) {
                return;
            }
            MessageRoute route = messageRouter.route(text, imageItem != null);
            switch (route) {
                case IMAGE_UNDERSTANDING ->
                        replyWithImageUnderstanding(activeClient, userId, text, imageItem);
                case IMAGE_GENERATION ->
                        replyWithGeneratedImage(activeClient, userId, text);
                case TEXT_CHAT -> {
                    String answer = processMessage(userId, text);
                    activeClient.sendText(userId, answer);
                }
            }
            System.out.println("  -> 已回复");
        } catch (CliException e) {
            LOG.warn("消息处理失败 [{}]: {}", e.getErrorCode(), e.getUserMessage());
            if (!stopping.get()) {
                sendSafely(activeClient, userId, userFacingError(e));
            }
        } catch (Exception e) {
            LOG.warn("消息回复失败: {}", e.getMessage());
            if (!stopping.get()) {
                sendSafely(activeClient, userId, "服务暂时不可用，请稍后重试。");
            }
        }
    }

    private void replyWithImageUnderstanding(
            ILinkClient activeClient,
            String userId,
            String question,
            MessageItem imageItem) throws IOException {
        byte[] imageBytes = activeClient.downloadImageFromMessageItem(imageItem);
        String answer = imageUnderstandingClientProvider.getObject()
                .analyze(imageBytes, detectImageMimeType(imageBytes), question);
        activeClient.sendText(userId, answer);
    }

    private void replyWithGeneratedImage(
            ILinkClient activeClient,
            String userId,
            String prompt) throws IOException {
        GeneratedImage generated = imageGenerationClientProvider.getObject().generate(prompt);
        // caption 传 null，保证一次文生图只发送一张图片，避免文字和图片形成两次回复。
        activeClient.sendImage(userId, generated.data(), generated.fileName(), null);
    }

    private void sendSafely(ILinkClient activeClient, String userId, String text) {
        try {
            activeClient.sendText(userId, text);
        } catch (Exception sendError) {
            LOG.warn("错误提示发送失败: {}", sendError.getMessage());
        }
    }

    private String userFacingError(CliException error) {
        return switch (error.getErrorCode()) {
            case CONFIG_ERROR -> "AI 服务尚未配置完成，请联系管理员。";
            case NETWORK_ERROR -> "AI 服务暂时不可用，请稍后重试。";
            default -> "消息处理失败，请稍后重试。";
        };
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

    private MessageItem firstImageItem(WeixinMessage message) {
        if (message.getItem_list() == null) {
            return null;
        }
        for (MessageItem item : message.getItem_list()) {
            if (item != null && item.getImage_item() != null
                    && item.getImage_item().getMedia() != null) {
                return item;
            }
        }
        return null;
    }

    private String detectImageMimeType(byte[] image) {
        if (image.length >= 8
                && image[0] == (byte) 0x89
                && image[1] == 0x50
                && image[2] == 0x4E
                && image[3] == 0x47) {
            return "image/png";
        }
        if (image.length >= 12
                && image[0] == 'R'
                && image[1] == 'I'
                && image[2] == 'F'
                && image[3] == 'F'
                && image[8] == 'W'
                && image[9] == 'E'
                && image[10] == 'B'
                && image[11] == 'P') {
            return "image/webp";
        }
        if (image.length >= 2 && image[0] == 'B' && image[1] == 'M') {
            return "image/bmp";
        }
        return "image/jpeg";
    }

    /** 智能消息处理: XX天气 → 查天气 | 管理命令/其他文本 → DeepSeek */
    private String processMessage(String userId, String text) {
        if (text.stripLeading().startsWith("/")) {
            return getChatService().chat(userId, text);
        }

        String city = extractCity(text);
        if (city != null) {
            String weather = tryWeather(city);
            return weather != null ? weather : "天气服务暂时不可用，请稍后重试。";
        }

        return getChatService().chat(userId, text);
    }

    private String tryWeather(String city) {
        try {
            WeatherResult r = weatherService.query(city);
            return formatWeather(r);
        } catch (Exception e) {
            LOG.warn("天气查询失败: {}", e.getMessage());
            return null;
        }
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

    private ChatService getChatService() {
        return chatServiceProvider.getObject();
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
            Files.writeString(SESSION_FILE, objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.warn("无法保存 iLink 会话: {}", e.getMessage());
        }
    }

    private ResumeContext loadSession() {
        if (!Files.exists(SESSION_FILE)) {
            return null;
        }
        try {
            SessionData data = objectMapper.readValue(
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
            LOG.warn("无法读取 iLink 会话，将重新登录: {}", e.getMessage());
            deleteSessionFile();
            return null;
        }
    }

    private void deleteSessionFile() {
        try {
            Files.deleteIfExists(SESSION_FILE);
        } catch (IOException e) {
            LOG.warn("无法删除失效的 iLink 会话: {}", e.getMessage());
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            stopping.set(true);
        }
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
        chatDispatcher.close();
        closeClient();
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
