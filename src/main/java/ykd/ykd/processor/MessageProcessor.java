package ykd.ykd.processor;

import com.github.wechat.ilink.sdk.ILinkClient;
import com.github.wechat.ilink.sdk.core.model.CDNMedia;
import com.github.wechat.ilink.sdk.core.model.MessageItem;
import com.github.wechat.ilink.sdk.core.model.WeixinMessage;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import ykd.ykd.document.DocumentParsingService;
import ykd.ykd.llm.tools.DocumentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.exception.GlobalExceptionHandler;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.task.ImageBatchManager;
import ykd.ykd.task.ReminderTaskManager;
import ykd.ykd.task.VideoTaskManager;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
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
    private final ImageBatchManager imageBatchManager;
    private final UserContext userContext;
    private final DocumentParsingService documentParsingService;
    private final Queue<ProcessResult> completedVideos = new ConcurrentLinkedQueue<>();
    private final Queue<ProcessResult> completedReminders = new ConcurrentLinkedQueue<>();
    private final Queue<ProcessResult> completedImageBatches = new ConcurrentLinkedQueue<>();
    private final Queue<ProcessResult> voiceQueue;

    public MessageProcessor(LlmService llmService,
                            ChatClient deepseekClient,
                            ChatClient agnesClient,
                            VideoTaskManager videoTaskManager,
                            ReminderTaskManager reminderTaskManager,
                            ImageBatchManager imageBatchManager,
                            UserContext userContext,
                            Queue<ProcessResult> voiceQueue,
                            DocumentParsingService documentParsingService) {
        this.llmService = llmService;
        this.deepseekClient = deepseekClient;
        this.agnesClient = agnesClient;
        this.videoTaskManager = videoTaskManager;
        this.reminderTaskManager = reminderTaskManager;
        this.imageBatchManager = imageBatchManager;
        this.userContext = userContext;
        this.voiceQueue = voiceQueue;
        this.documentParsingService = documentParsingService;
    }

    /**
     * 初始化：注册视频完成回调，将结果入队等待 BotService 拉取发送。
     */
    @PostConstruct
    public void init() {
        videoTaskManager.setOnCompleted(completedVideos::add);
        reminderTaskManager.setOnCompleted(completedReminders::add);
        imageBatchManager.setOnBatchReady(this::processImageBatch);
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
     * 拉取已完成的图片批次结果（非阻塞）。
     */
    public ProcessResult pollCompletedImageBatch() {
        return completedImageBatches.poll();
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

        if (hasFileItem(msg)) {
            log.info("[Processor] 检测到文件消息，自动解析: userId={}", fromUserId);
            String[] result = new String[1];
            userContext.executeAs(fromUserId, () -> {
                try {
                    String fileName = DocumentTools.downloadParseAndCache(
                            msg, client, fromUserId, documentParsingService);
                    if (fileName == null) {
                        result[0] = "❌ 文件下载或解析失败，请稍后重试";
                        return;
                    }

                    String cachedContent = DocumentTools.getCachedContent(fromUserId);
                    String prompt = String.format(
                            "用户发送了文件「%s」，以下是文件内容：\n---\n%s\n---\n\n请给出简要总结，并告知用户可以继续对文件内容提问。",
                            fileName, cachedContent);
                    String reply = llmService.chat(prompt, List.of(), deepseekClient, fromUserId);
                    result[0] = reply;
                } catch (Exception e) {
                    log.error("[Processor] 文件处理失败: {}", e.getMessage(), e);
                    result[0] = "❌ 文件处理失败，请稍后重试";
                }
            });
            return ProcessResult.text(result[0] != null ? result[0] : "❌ 文件处理失败", fromUserId);
        }

        if (DocumentTools.hasCachedDocument(fromUserId)) {
            String voiceText = extractVoiceText(msg);
            String text = extractText(msg);
            if (voiceText != null && !voiceText.isBlank()) {
                text = (text != null) ? text + " " + voiceText : voiceText;
            }

            if (text != null && !text.isBlank()) {
                String cachedContent = DocumentTools.getCachedContent(fromUserId);
                String cachedFileName = DocumentTools.getCachedFileName(fromUserId);
                log.info("[Processor] 用户对文件追问: userId={}, question={}", fromUserId, text);

                String[] result = new String[1];
                String finalText = text;
                userContext.executeAs(fromUserId, () -> {
                    try {
                        String prompt = String.format(
                                "用户之前发送了文件「%s」，以下是文件内容：\n---\n%s\n---\n\n用户现在针对该文件提问：%s\n请根据文件内容回答用户的问题。",
                                cachedFileName, cachedContent, finalText);
                        String reply = llmService.chat(prompt, List.of(), deepseekClient, fromUserId);
                        result[0] = reply;
                    } catch (Exception e) {
                        log.error("[Processor] 文件追问处理失败: {}", e.getMessage(), e);
                        result[0] = "❌ 处理失败，请稍后重试";
                    }
                });
                return ProcessResult.text(result[0] != null ? result[0] : "❌ 处理失败", fromUserId);
            }
        }

        // iLink 平台自动识别语音消息中的文字
        String voiceText = extractVoiceText(msg);

        String text = extractText(msg);
        List<String> imageDataUris = extractImageDataUris(msg, client);

        if (voiceText != null && !voiceText.isBlank()) {
            text = (text != null) ? text + " " + voiceText : voiceText;
        }

        if (text == null && imageDataUris.isEmpty()) {
            log.info("[Processor] 不支持的消息类型: userId={}, itemCount={}", fromUserId, msg.getItem_list().size());
            return null;
        }

        // 有图片时走批处理：图片进缓冲区，定时器触发后合并处理
        if (!imageDataUris.isEmpty()) {
            for (String imageUri : imageDataUris) {
                imageBatchManager.addImage(fromUserId, imageUri, text);
            }
            log.info("[Processor] 图片已交给批处理: userId={}, imageCount={}", fromUserId, imageDataUris.size());
            return null;
        }

        String textPreview = text != null ? (text.length() > 100 ? text.substring(0, 100) + "..." : text) : null;
        log.info("[Processor] 收到消息: userId={}, model=DeepSeek, text={}", fromUserId, textPreview);

        var result = new ProcessResult[1];
        String finalText = text;
        userContext.executeAs(fromUserId, () -> {
            try {
                String reply = llmService.chat(finalText, List.of(), deepseekClient, fromUserId);
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
     * 从微信消息中提取所有图片，下载 CDN 图片并编码为 base64 data URI。
     *
     * <p>注意：不能使用 ImageItem.getUrl()，微信 iLink 图片存储在 CDN 上不暴露公网 URL。
     * 正确路径是 CDNMedia → client.downloadMedia() 下载字节 → Base64 编码。</p>
     *
     * @param msg    微信 iLink 消息对象
     * @param client iLink 客户端，用于调用 {@code downloadMedia()}
     * @return base64 data URI 列表，无图片时返回空列表
     */
    private List<String> extractImageDataUris(WeixinMessage msg, ILinkClient client) {
        List<String> uris = new ArrayList<>();
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
                        uris.add("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(imageBytes));
                    } else {
                        log.warn("[Processor] CDN图片下载返回空: userId={}", msg.getFrom_user_id());
                    }
                }
            }
        }
        return uris;
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
            String url = m.group().replaceAll("[);]+$", "");
            log.info("[Processor] 提取到URL: {}", url);
            return url;
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

    /**
     * 处理图片批次回调，由 ImageBatchManager 定时器触发。
     *
     * <p>将缓冲区中的多张图片合并，一起交给 AI 处理，结果入队等待 BotService 发送。</p>
     */
    private void processImageBatch(ImageBatchManager.ImageBatch batch) {
        String userId = batch.userId();
        List<String> imageUris = batch.imageUris();
        String rawText = batch.text();
        final String text = (rawText == null || rawText.isBlank()) ? "请描述这些图片" : rawText;

        log.info("[Processor] 图片批次处理: userId={}, imageCount={}, text={}", userId, imageUris.size(), text);

        userContext.executeAs(userId, () -> {
            try {
                String reply = llmService.chat(text, imageUris, agnesClient, userId);
                log.info("[Processor] 图片批次回复: userId={}, reply={}", userId,
                        reply != null ? reply.substring(0, Math.min(100, reply.length())) : null);
                completedImageBatches.add(ProcessResult.text(reply, userId));
            } catch (Exception e) {
                log.error("[Processor] 图片批次处理失败: userId={}, error={}", userId, e.getMessage(), e);
                completedImageBatches.add(ProcessResult.text("图片识别失败，请稍后重试", userId));
            }
        });
    }
    private boolean hasFileItem(WeixinMessage msg) {
        for (MessageItem item : msg.getItem_list()) {
            if (item.getFile_item() != null) {
                return true;
            }
        }
        return false;
    }
}
