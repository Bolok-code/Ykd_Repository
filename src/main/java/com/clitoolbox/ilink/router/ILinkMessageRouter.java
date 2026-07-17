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

    public MessageRoute route(String text, boolean containsImage) {
        if (containsImage) {
            return MessageRoute.IMAGE_UNDERSTANDING;
        }
        if (isImageGenerationIntent(text)) {
            return MessageRoute.IMAGE_GENERATION;
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

    public enum MessageRoute {
        TEXT_CHAT,
        IMAGE_UNDERSTANDING,
        IMAGE_GENERATION
    }
}
