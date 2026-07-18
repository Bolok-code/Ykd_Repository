package com.clitoolbox.ilink.router;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.clitoolbox.ilink.router.ILinkMessageRouter.MessageRoute;
import org.junit.jupiter.api.Test;

class ILinkMessageRouterTest {
    private final ILinkMessageRouter router = new ILinkMessageRouter();

    @Test
    void routesAnyImageToVisionUnderstanding() {
        assertEquals(
                MessageRoute.IMAGE_UNDERSTANDING,
                router.route("这道题怎么做？", true));
        assertEquals(
                MessageRoute.IMAGE_UNDERSTANDING,
                router.route(null, true));
    }

    @Test
    void recognizesNaturalLanguageImageGenerationIntent() {
        assertEquals(
                MessageRoute.IMAGE_GENERATION,
                router.route("帮我生成一张宇航员在月球上喝咖啡的图片", false));
        assertEquals(
                MessageRoute.IMAGE_GENERATION,
                router.route("请画一幅夏日荷塘", false));
    }

    @Test
    void leavesNormalQuestionsForTextChat() {
        assertEquals(
                MessageRoute.TEXT_CHAT,
                router.route("给我讲讲月球的形成过程", false));
    }

    @Test
    void recognizesVoiceReplyIntentAndExtractsQuestion() {
        assertEquals(
                MessageRoute.VOICE_REPLY,
                router.route("请用语音回答：明天杭州天气", false));
        assertEquals(
                "明天杭州天气",
                router.extractVoiceQuestion("请用语音回答：明天杭州天气"));
    }
}
