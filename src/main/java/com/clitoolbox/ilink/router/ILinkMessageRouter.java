package com.clitoolbox.ilink.router;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 根据微信消息内容选择聊天、图片识别或文生图能力，不要求用户输入命令。
 */
@Component
public class ILinkMessageRouter {
    private static final Pattern IMAGE_GENERATION_PATTERN = Pattern.compile(
            "(生成|画|绘制|创作|制作|设计|做).{0,12}"
                    + "(图|图片|图像|海报|头像|壁纸|插画|漫画|照片)"
                    + "|(图|图片|图像|海报|头像|壁纸|插画|漫画|照片).{0,12}"
                    + "(生成|画|绘制|创作|制作|设计|做)");
    private static final List<String> IMAGE_GENERATION_PHRASES = List.of(
            "生成一张",
            "生成一幅",
            "生成图片",
            "生成个图片",
            "画一张",
            "画一幅",
            "画个",
            "绘制一张",
            "绘制一幅",
            "做一张图",
            "做个图",
            "帮我画",
            "帮我生成",
            "文生图",
            "text to image",
            "text-to-image");
    private static final List<String> VOICE_REPLY_PHRASES = List.of(
            "用语音回复",
            "请用语音回复",
            "用语音回答",
            "请用语音回答",
            "语音回答",
            "用语音告诉我",
            "语音告诉我",
            "发语音说",
            "发一段语音");

    public MessageRoute route(String text, boolean containsImage) {
        if (containsImage) {
            return MessageRoute.IMAGE_UNDERSTANDING;
        }
        if (isImageGenerationIntent(text)) {
            return MessageRoute.IMAGE_GENERATION;
        }
        if (isVoiceReplyIntent(text)) {
            return MessageRoute.VOICE_REPLY;
        }
        return MessageRoute.TEXT_CHAT;
    }

    boolean isImageGenerationIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return IMAGE_GENERATION_PHRASES.stream().anyMatch(normalized::contains)
                || IMAGE_GENERATION_PATTERN.matcher(normalized).find();
    }

    boolean isVoiceReplyIntent(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        return VOICE_REPLY_PHRASES.stream().anyMatch(normalized::contains);
    }

    /**
     * 删除常见的“用语音回复”前缀，让天气和普通聊天继续处理真正的问题。
     */
    public String extractVoiceQuestion(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }
        String result = text.trim();
        for (String phrase : VOICE_REPLY_PHRASES) {
            int index = result.indexOf(phrase);
            if (index >= 0) {
                result = result.substring(index + phrase.length())
                        .replaceFirst("^[：:，,。\\s]+", "")
                        .trim();
                break;
            }
        }
        return result.isBlank() ? "请和我打个招呼" : result;
    }

    public enum MessageRoute {
        TEXT_CHAT,
        IMAGE_UNDERSTANDING,
        IMAGE_GENERATION,
        VOICE_REPLY
    }
}
