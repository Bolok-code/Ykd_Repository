package com.clitoolbox.ilink.router;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.intent.IntentClassifier;
import com.clitoolbox.intent.IntentDecision;
import com.clitoolbox.intent.IntentType;
import com.clitoolbox.intent.ReplyMode;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * 将 iLink 消息类型和豆包返回的结构化意图转换成项目内部处理路线。
 *
 * <p>图片等确定的消息格式由代码判断；文字表达的业务意图交给
 * {@link IntentClassifier}，这里不再用关键词或正则猜测用户意图。
 */
@Component
@Lazy
public class ILinkMessageRouter {
    private static final Logger LOG = LoggerFactory.getLogger(ILinkMessageRouter.class);

    private final IntentClassifier intentClassifier;

    public ILinkMessageRouter(IntentClassifier intentClassifier) {
        this.intentClassifier = intentClassifier;
    }

    public RoutingDecision route(String text, boolean containsImage) {
        if (containsImage) {
            return new RoutingDecision(
                    MessageRoute.IMAGE_UNDERSTANDING,
                    ReplyMode.TEXT,
                    text,
                    null,
                    null,
                    1.0);
        }
        if (text == null || text.isBlank()) {
            return fallbackTextChat("请和我打个招呼");
        }

        String normalized = text.trim();
        // 斜杠命令是项目定义的精确协议，不属于自然语言意图识别。
        if (normalized.startsWith("/")) {
            return fallbackTextChat(normalized);
        }

        try {
            IntentDecision intent = intentClassifier.classify(normalized);
            LOG.info(
                    "意图识别完成 - model={}, intent={}, replyMode={}, confidence={}",
                    intentClassifier.modelName(),
                    intent.intent(),
                    intent.replyMode(),
                    String.format("%.2f", intent.confidence()));
            return toRoutingDecision(intent);
        } catch (CliException e) {
            LOG.warn(
                    "意图识别失败，回落普通聊天 [{}]: {}",
                    e.getErrorCode(),
                    e.getUserMessage());
            return fallbackTextChat(normalized);
        } catch (RuntimeException e) {
            LOG.warn("意图识别失败，回落普通聊天: {}", e.getMessage());
            return fallbackTextChat(normalized);
        }
    }

    private RoutingDecision toRoutingDecision(IntentDecision intent) {
        MessageRoute route = switch (intent.intent()) {
            case TEXT_CHAT -> MessageRoute.TEXT_CHAT;
            case WEATHER_QUERY -> MessageRoute.WEATHER_QUERY;
            case IMAGE_GENERATION -> MessageRoute.IMAGE_GENERATION;
        };
        return new RoutingDecision(
                route,
                intent.replyMode(),
                intent.requestText(),
                intent.city(),
                intent.targetDate(),
                intent.confidence());
    }

    private RoutingDecision fallbackTextChat(String text) {
        return new RoutingDecision(
                MessageRoute.TEXT_CHAT,
                ReplyMode.TEXT,
                text,
                null,
                null,
                0.0);
    }

    public enum MessageRoute {
        TEXT_CHAT,
        WEATHER_QUERY,
        IMAGE_UNDERSTANDING,
        IMAGE_GENERATION
    }

    public record RoutingDecision(
            MessageRoute route,
            ReplyMode replyMode,
            String requestText,
            String city,
            LocalDate targetDate,
            double confidence) {

        public RoutingDecision {
            if (route == null) {
                throw new IllegalArgumentException("消息路线不能为空");
            }
            if (replyMode == null) {
                throw new IllegalArgumentException("回复形式不能为空");
            }
            city = city == null || city.isBlank() ? null : city.trim();
            requestText = requestText == null ? null : requestText.trim();
        }
    }
}
