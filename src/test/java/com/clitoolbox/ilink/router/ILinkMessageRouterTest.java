package com.clitoolbox.ilink.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.ilink.router.ILinkMessageRouter.MessageRoute;
import com.clitoolbox.ilink.router.ILinkMessageRouter.RoutingDecision;
import com.clitoolbox.intent.IntentClassifier;
import com.clitoolbox.intent.IntentContext;
import com.clitoolbox.intent.IntentDecision;
import com.clitoolbox.intent.IntentType;
import com.clitoolbox.intent.ReplyMode;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ILinkMessageRouterTest {
    private final StubIntentClassifier classifier = new StubIntentClassifier();
    private final ILinkMessageRouter router = new ILinkMessageRouter(classifier);

    @Test
    void routesAnyImageWithoutCallingIntentModel() {
        classifier.failure = new AssertionError("图片消息不应调用意图模型");

        RoutingDecision withQuestion = router.route("这道题怎么做？", true);
        RoutingDecision withoutQuestion = router.route(null, true);

        assertEquals(MessageRoute.IMAGE_UNDERSTANDING, withQuestion.route());
        assertEquals(MessageRoute.IMAGE_UNDERSTANDING, withoutQuestion.route());
        assertEquals(0, classifier.calls);
    }

    @Test
    void routesImageGenerationFromStructuredModelDecision() {
        classifier.decision = new IntentDecision(
                IntentType.IMAGE_GENERATION,
                ReplyMode.TEXT,
                0.98,
                null,
                null,
                "宇航员在月球上喝咖啡");

        RoutingDecision decision =
                router.route("帮我生成一张宇航员在月球上喝咖啡的图片", false);

        assertEquals(MessageRoute.IMAGE_GENERATION, decision.route());
        assertEquals("宇航员在月球上喝咖啡", decision.requestText());
    }

    @Test
    void keepsBusinessIntentAndVoiceReplyAsIndependentDimensions() {
        LocalDate tomorrow = LocalDate.of(2026, 7, 21);
        classifier.decision = new IntentDecision(
                IntentType.WEATHER_QUERY,
                ReplyMode.VOICE,
                0.97,
                "杭州",
                tomorrow,
                "明天杭州天气");

        RoutingDecision decision =
                router.route("请用语音回答：明天杭州天气", false);

        assertEquals(MessageRoute.WEATHER_QUERY, decision.route());
        assertEquals(ReplyMode.VOICE, decision.replyMode());
        assertEquals("杭州", decision.city());
        assertEquals(tomorrow, decision.targetDate());
    }

    @Test
    void fallsBackToNormalChatWhenIntentModelFails() {
        classifier.failure = new CliException(
                ErrorCode.NETWORK_ERROR,
                "模拟豆包不可用");

        RoutingDecision decision = router.route("给我讲讲月球的形成过程", false);

        assertEquals(MessageRoute.TEXT_CHAT, decision.route());
        assertEquals(ReplyMode.TEXT, decision.replyMode());
        assertEquals("给我讲讲月球的形成过程", decision.requestText());
    }

    @Test
    void slashCommandsBypassIntentModel() {
        classifier.failure = new AssertionError("斜杠命令不应调用意图模型");

        RoutingDecision decision = router.route("/clear", false);

        assertEquals(MessageRoute.TEXT_CHAT, decision.route());
        assertEquals("/clear", decision.requestText());
        assertEquals(0, classifier.calls);
    }

    @Test
    void passesPreviousBusinessContextToIntentModel() {
        IntentContext context = new IntentContext(
                "WEATHER_QUERY",
                "Xuzhou",
                LocalDate.of(2026, 7, 21));

        router.route("the day after tomorrow?", false, context);

        assertEquals(context, classifier.receivedContext);
    }

    private static final class StubIntentClassifier implements IntentClassifier {
        private IntentDecision decision =
                IntentDecision.textChat("默认聊天");
        private Throwable failure;
        private int calls;
        private IntentContext receivedContext;

        @Override
        public IntentDecision classify(String text) {
            calls++;
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            return decision;
        }

        @Override
        public IntentDecision classify(String text, IntentContext context) {
            receivedContext = context;
            return classify(text);
        }

        @Override
        public String modelName() {
            return "test-intent-model";
        }
    }
}
