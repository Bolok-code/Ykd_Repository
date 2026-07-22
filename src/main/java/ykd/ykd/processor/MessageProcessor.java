package ykd.ykd.processor;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.exception.GlobalExceptionHandler;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.processor.VideoTaskManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.Base64;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 微信消息处理器，负责消息的提取、路由与 AI 回复的编排。
 *
 * <p>从 {@link WeixinMessage} 中提取文本和图片，根据内容类型路由到对应的 AI 模型：
 * 纯文本走 DeepSeek，包含图片走 Agnes Flash 多模态模型。AI 回复中包含图片 URL 时自动
 * 下载并转为 {@link ProcessResult#image(byte[])}，否则以文本形式返回。</p>
 *
 * <h3>处理流程</h3>
 * <ol>
 *   <li>提取文本和图片（CDN → base64 data URI）</li>
 *   <li>根据是否有图片选择 ChatClient（Agnes / DeepSeek）</li>
 *   <li>调用 {@link LlmService#chat(String, String, ChatClient)}</li>
 *   <li>检查回复中是否包含图片 URL，有则下载并返回图片</li>
 *   <li>无图片 URL 则直接返回文本</li>
 * </ol>
 */
@Slf4j
@Component
public class MessageProcessor {

    /**
     * 用于从 AI 回复中提取图片 URL 的正则表达式。
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+");

    private final LlmService llmService;
    private final ChatClient deepseekClient;
    private final ChatClient agnesClient;
    private final VideoTaskManager videoTaskManager;
    private final ReminderTaskManager reminderTaskManager;
    private final UserContext userContext;
    private final Queue<ProcessResult> completedVideos = new ConcurrentLinkedQueue<>();
    private final Queue<ProcessResult> completedReminders = new ConcurrentLinkedQueue<>();
    private final Queue<ProcessResult> voiceQueue;

    public MessageProcessor(LlmService llmService,
                            ChatClient deepseekClient,
                            ChatClient agnesClient,
                            VideoTaskManager videoTaskManager,
                            ReminderTaskManager reminderTaskManager,
                            UserContext userContext,
                            Queue<ProcessResult> voiceQueue) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.agnesClient = agnesClient;
        this.videoTaskManager = videoTaskManager;
        this.reminderTaskManager = reminderTaskManager;
        this.userContext = userContext;
        this.voiceQueue = voiceQueue;
    }

    /**
     * 初始化：注册视频完成回调，将结果入队等待 BotService 拉取发送。
     */
    @PostConstruct
    public void init() {
        videoTaskManager.setOnCompleted(completedVideos::add);
        reminderTaskManager.setOnCompleted(completedReminders::add);
    }

    /**
     * 拉取已完成的视频结果（非阻塞）。
     *
     * <p>由 {@code WeixinBotService} 在主循环中调用，处理后台完成的视频推送。</p>
     *
     * @return 视频 {@link ProcessResult}，无完成视频时返回 {@code null}
     */
    public ProcessResult pollCompletedVideo() {
        return completedVideos.poll();
    }

    /**
     * 拉取已到期的提醒结果（非阻塞）。
     */
    public ProcessResult pollCompletedReminder() {
        return completedReminders.poll();
    }

    /**
     * 处理一条微信消息，返回处理结果。
     *
     * <p>如果消息无可处理的内容（既无文本也无图片），返回 {@code null}。
     * 正常处理返回 {@link ProcessResult}，调用方根据 {@code type()} 判断是文本还是图片后发送。</p>
     *
     * @param msg    微信 iLink 消息对象
     * @param client iLink 客户端，用于从 CDN 下载图片
     * @return 处理结果，无可处理内容返回 {@code null}
     */
    public ProcessResult process(WeixinMessage msg, ILinkClient client) {
        long start = System.currentTimeMillis();
        String fromUserId = msg.getFrom_user_id();

        if (msg.getItem_list() == null) {
            log.info("[Processor] 收到空消息(无item_list): userId={}", fromUserId);
            return null;
        }

        // iLink 平台自动识别语音消息中的文字
        String voiceText = extractVoiceText(msg);

        String text = extractText(msg);
        String imageDataUri = extractImageDataUri(msg, client);

        if (voiceText != null && !voiceText.isBlank()) {
            text = (text != null) ? text + " " + voiceText : voiceText;
        }

        if (text == null && imageDataUri == null) {
            log.info("[Processor] 不支持的消息类型: userId={}, itemCount={}", fromUserId, msg.getItem_list().size());
            return null;
        }

        String textPreview = text != null ? (text.length() > 100 ? text.substring(0, 100) + "..." : text) : null;
        ChatClient aiClient = (imageDataUri != null) ? agnesClient : deepseekClient;
        String modelName = (imageDataUri != null) ? "Agnes" : "DeepSeek";

        log.info("[Processor] 收到消息: userId={}, model={}, text={}, hasImage={}",
                fromUserId, modelName, textPreview, imageDataUri != null);

        var result = new ProcessResult[1];
        String finalText = text;
        userContext.executeAs(fromUserId, () -> {
            try {
                String reply = llmService.chat(finalText, imageDataUri, aiClient, fromUserId);
                long elapsed = System.currentTimeMillis() - start;
                String replyPreview = reply != null ? (reply.length() > 200 ? reply.substring(0, 200) + "..." : reply) : null;
                log.info("[Processor] 回复成功: elapsed={}ms, userId={}, reply={}", elapsed, fromUserId, replyPreview);

                /*
                 * 回复结果优先级：语音 > 图片 > 文字。
                 * VoiceTools.speak() 在 chat() 内部同步执行，音频在 chat() 返回时必定已入队，
                 * poll() 非阻塞获取，有则直接返回 VOICE 类型。
                 */
                ProcessResult voiceResult = voiceQueue.poll();
                if (voiceResult != null) {
                    result[0] = voiceResult;
                    return;
                }

                String url = extractUrl(reply);
                if (url != null) {
                    byte[] imageData = downloadImage(url);
                    if (imageData != null) {
                        result[0] = ProcessResult.image(imageData, fromUserId);
                        return;
                    }
                    log.warn("[Processor] 图片下载失败，降级发送文字: userId={}", fromUserId);
                }
                result[0] = ProcessResult.text(reply, fromUserId);
            } catch (Exception e) {
                long elapsed = System.currentTimeMillis() - start;
                log.error("[Processor] 回复失败: elapsed={}ms, userId={}, text={}, error={}", elapsed, fromUserId, finalText, e.getMessage(), e);
                result[0] = ProcessResult.text(GlobalExceptionHandler.toUserMessage(e), fromUserId);
            }
        });
        return result[0];
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

    /**
     * 从微信消息中提取第一张图片，下载 CDN 图片并编码为 base64 data URI。
     *
     * <p>注意：不能使用 ImageItem.getUrl()，微信 iLink 图片存储在 CDN 上不暴露公网 URL。
     * 正确路径是 CDNMedia → client.downloadMedia() 下载字节 → Base64 编码。</p>
     *
     * @param msg    微信 iLink 消息对象
     * @param client iLink 客户端，用于调用 {@code downloadMedia()}
     * @return base64 data URI（如 {@code data:image/jpeg;base64,/9j...}），无图片时返回 {@code null}
     */
    private String extractImageDataUri(WeixinMessage msg, ILinkClient client) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getImage_item() != null) {
                CDNMedia media = item.getImage_item().getMedia();
                if (media != null) {
                    byte[] imageBytes;
                    try {
                        imageBytes = client.downloadMedia(media);
                    } catch (IOException e) {
                        throw new BusinessException(ErrorCode.CDN_DOWNLOAD_FAILED, e.getMessage());
                    }
                    if (imageBytes != null) {
                        log.info("[Processor] CDN图片下载成功: size={}KB", imageBytes.length / 1024);
                        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes);
                    }
                    log.warn("[Processor] CDN图片下载返回空: userId={}", msg.getFrom_user_id());
                }
            }
        }
        return null;
    }

    /**
     * 从微信消息中提取语音识别文本（iLink 平台自动识别）。
     */
    private String extractVoiceText(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getVoice_item() != null && item.getVoice_item().getText() != null) {
                String voiceText = item.getVoice_item().getText();
                log.info("[Processor] iLink 语音识别: text={}", voiceText);
                return voiceText;
            }
        }
        return null;
    }

    /**
     * 从文本中提取第一个 HTTP(S) URL。
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
            log.info("[Processor] 提取到URL: {}", m.group());
            return m.group();
        }
        return null;
    }

    /**
     * 从图片 URL 下载图片为字节数组。
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
            log.info("[Processor] 图片下载成功: url={}, size={}KB", imageUrl, data.length / 1024);
            return data;
        } catch (Exception e) {
            log.error("[Processor] 下载图片失败: url={}", imageUrl, e);
            return null;
        }
    }
}
