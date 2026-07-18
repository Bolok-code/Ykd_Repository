package ykd.ykd.wxbot;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.listener.OnLoginListener;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.llm.service.LlmService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信 iLink 智能机器人服务。
 *
 * <p>启动后通过二维码扫码登录微信，监听用户消息，根据消息类型路由到不同 AI 模型：
 * 纯文字走 DeepSeek，包含图片走 Agnes Flash 多模态模型。</p>
 */
@Slf4j

@Service
public class WeixinBotService {

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final ChatClient agnesClient;

    private ILinkClient client;
    private volatile boolean running = true;
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+");


    /**
     * 构造微信机器人服务，注入 LLM 服务及多个 AI 模型客户端。
     *
     * @param llmService      编排 AI 对话的 LLM 服务
     * @param deepseekClient  DeepSeek 纯文字模型客户端
     * @param agnesClient     Agnes Flash 多模态模型客户端
     */
    public WeixinBotService(LlmService llmService,
                            ChatClient deepseekClient,
                            ChatClient agnesClient) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.agnesClient = agnesClient;
    }

    /**
     * 启动微信机器人。
     *
     * <p>Spring Bean 初始化完成后由 {@link PostConstruct} 自动触发，
     * 通过守护线程异步执行 {@link #runBot()}，避免阻塞容器启动。</p>
     */
    @PostConstruct
    public void start() {
        Thread botThread = new Thread(this::runBot, "wx-bot");
        botThread.setDaemon(true);
        botThread.start();
    }

    /**
     * 优雅关闭微信机器人服务。
     *
     * <p>Spring 容器销毁 Bean 时自动调用：置位 running 标志通知轮询循环退出，
     * 随后关闭 iLink 客户端释放 HTTP 连接池等资源。</p>
     */
    @PreDestroy
    public void stop() {
        running = false;
        if (client != null) {
            try {
                client.close();
            } catch (Exception e) {
                log.warn("关闭iLink客户端异常", e);
            }
        }
    }

    /**
     * 微信机器人主循环，由守护线程 {@code wx-bot} 执行。
     *
     * <p>执行流程分为两个阶段：</p>
     * <ol>
     *   <li>扫码登录：构建 {@link ILinkClient}，获取二维码并阻塞等待用户扫码确认</li>
     *   <li>消息轮询：循环调用 {@code getUpdates()} 拉取消息，分发至 {@link #handleMessage(WeixinMessage)} 处理</li>
     * </ol>
     */
    private void runBot() {
        try {
            client = ILinkClient.builder()
                    .onLogin(new OnLoginListener() {
                        @Override
                        public void onLoginSuccess(LoginContext context) {
                            log.info("微信登录成功，botId = {}", context.getBotId());
                        }

                        @Override
                        public void onLoginFailure(Throwable throwable) {
                            log.error("微信登录失败: {}", throwable.getMessage());
                        }
                    })
                    .build();
            String qrCodeContent = client.executeLogin();
            log.info("========================================");
            log.info("请将以下URL转为二维码后，用微信扫码登录：");
            log.info("{}", qrCodeContent);
            log.info("========================================");

            LoginContext context = client.getLoginFuture().get();
            log.info("登录完成，botId = {}，开始监听消息...", context.getBotId());

            while (running) {
                try {
                    List<WeixinMessage> messages = client.getUpdates();
                    if (messages != null) {
                        for (WeixinMessage msg : messages) {
                            handleMessage(msg);
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.error("消息轮询异常，3秒后重试", e);
                        Thread.sleep(3000);
                    }
                }
            }
        } catch (Exception e) {
            log.error("微信机器人运行异常", e);
        }
    }

    /**
     * 消息调度：根据内容类型选择模型。
     *
     * <p>有图片走 Agnes Flash（多模态），纯文字走 DeepSeek。</p>
     *
     * @param msg 微信 iLink 消息对象，包含发送者 ID 和消息条目列表
     */
    private void handleMessage(WeixinMessage msg) {
        long start = System.currentTimeMillis();
        String fromUserId = msg.getFrom_user_id();

        if (msg.getItem_list() == null) {
            log.info("[Bot] 收到空消息(无item_list): userId={}", fromUserId);
            return;
        }

        String text = extractText(msg);
        String imageDataUri = extractImageDataUri(msg);

        if (text == null && imageDataUri == null) {
            log.info("[Bot] 不支持的消息类型: userId={}, itemCount={}", fromUserId, msg.getItem_list().size());
            return;
        }

        String textPreview = text != null ? (text.length() > 100 ? text.substring(0, 100) + "..." : text) : null;
        ChatClient aiClient = (imageDataUri != null) ? agnesClient : deepseekClient;
        String modelName = (imageDataUri != null) ? "Agnes" : "DeepSeek";

        log.info("[Bot] 收到消息: userId={}, model={}, text={}, hasImage={}",
                fromUserId, modelName, textPreview, imageDataUri != null);
        try {
            String reply = llmService.chat(text, imageDataUri, aiClient);
            long elapsed = System.currentTimeMillis() - start;
            String replyPreview = reply != null ? (reply.length() > 200 ? reply.substring(0, 200) + "..." : reply) : null;
            log.info("[Bot] 回复成功: elapsed={}ms, userId={}, reply={}", elapsed, fromUserId, replyPreview);
            String url = extractUrl(reply);
            if (url != null) {
                byte[] imageData = downloadImage(url);
                if (imageData != null) {
                    safeSendImage(fromUserId, imageData);
                } else {
                    log.warn("[Bot] 图片下载失败，降级发送文字: userId={}", fromUserId);
                    safeSendText(fromUserId, reply);
                }
            } else {
                safeSendText(fromUserId, reply);
            }
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[Bot] 回复失败: elapsed={}ms, userId={}, text={}, error={}", elapsed, fromUserId, text, e.getMessage(), e);
            safeSendText(fromUserId, "❌ " + ErrorCode.AI_CALL_FAILED.getDefaultMessage());
        }
    }

    /**
     * 安全发送文本消息。
     *
     * <p>异常仅记录日志不向上抛出，确保发送失败不会中断主流程。</p>
     *
     * @param userId 微信用户 ID
     * @param text   待发送的文本内容
     */
    private void safeSendText(String userId, String text) {
        try {
            client.sendText(userId, text);
        } catch (Exception e) {
            log.error("发送消息失败: userId={}, text={}", userId, text, e);
        }
    }

    /**
     * 安全发送图片消息。
     *
     * <p>异常仅记录日志不向上抛出，确保发送失败不会中断主流程。</p>
     *
     * @param userId    微信用户 ID
     * @param imageData 图片字节数据
     */
    private void safeSendImage(String userId, byte[] imageData) {
        try {
            client.sendImage(userId, imageData, "image.png", "");
            log.info("[Bot] 图片发送成功: userId={}, size={}KB", userId, imageData.length / 1024);
        } catch (Exception e) {
            log.error("发送图片失败: userId={}", userId, e);
        }
    }

    /**
     * 从图片 URL 下载为字节数组。
     *
     * @param imageUrl 图片 URL
     * @return 图片字节数据，下载失败返回 {@code null}
     */
    private byte[] downloadImage(String imageUrl) {
        try (InputStream in = URI.create(imageUrl).toURL().openStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            byte[] data = out.toByteArray();
            log.info("[Bot] 图片下载成功: url={}, size={}KB", imageUrl, data.length / 1024);
            return data;
        } catch (Exception e) {
            log.error("下载图片失败: url={}", imageUrl, e);
            return null;
        }
    }

    /**
     * 从文本中提取第一个 URL。
     *
     * @param text 待匹配文本
     * @return 匹配到的 URL，无匹配时返回 {@code null}
     */
    private String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher m = URL_PATTERN.matcher(text);
        if (m.find()) {
            String url = m.group();
            log.info("[Bot] 提取到URL: {}", url);
            return url;
        }
        return null;
    }


    /**
     * 从微信消息条目列表中提取第一条文本内容。
     *
     * @param msg 微信 iLink 消息对象
     * @return 提取到的文本字符串，无文本内容时返回 {@code null}
     */
    private String extractText(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getText_item() != null) {
                return item.getText_item().getText();
            }
        }
        return null;
    }

  /*旧方案为什么不行
    反射调试已经证实：ImageItem 根本没有 getUrl() 方法。它只有 getMedia()（返回 CDNMedia）、getAeskey()、getThumb_size() 等。微信 iLink 的图片存在 CDN 上，不暴露公网 URL。所以 getUrl() 永远返回 null，整个 extractImageUrl() 形同虚设，纯图片消息进不了图片分支，直接被打上"不支持的消息类型"丢弃了。
    新方案为什么行
    走 iLink SDK 的正确路径：CDNMedia → client.downloadMedia() 下载字节 → Base64 编码为 data:image/jpeg;base64,... 传给 AI。URI.create() 原生支持 data: 协议，LlmServiceImpl 无需任何改动。
*/
    /**
     * 从微信消息条目列表中提取第一张图片，下载并转为 base64 data URI。
     *
     * @param msg 微信 iLink 消息对象
     * @return base64 data URI，无图片或下载失败时返回 {@code null}
     */
    private String extractImageDataUri(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                CDNMedia media = item.getImage_item().getMedia();
                if (media != null) {
                    byte[] imageBytes = null;
                    try {
                        imageBytes = client.downloadMedia(media);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    if (imageBytes != null) {
                        log.info("[Bot] CDN图片下载成功: size={}KB", imageBytes.length / 1024);
                        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);
                    }
                    log.warn("[Bot] CDN图片下载返回空: userId={}", msg.getFrom_user_id());
                }
            }
        }
        return null;
    }
}
