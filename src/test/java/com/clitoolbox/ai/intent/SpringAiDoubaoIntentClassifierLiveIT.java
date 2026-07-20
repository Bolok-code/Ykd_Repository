package com.clitoolbox.ai.intent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.clitoolbox.intent.IntentClassifier;
import com.clitoolbox.intent.IntentDecision;
import com.clitoolbox.intent.IntentType;
import com.clitoolbox.intent.ReplyMode;
import com.clitoolbox.weather.WeatherService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 手动真实 API 测试，不符合 Surefire 默认 *Test 命名，因此普通 mvn test 不会消耗额度。
 *
 * <p>需要在 config/application-local.yml 或 ARK_API_KEY 中配置真实密钥后执行：
 * {@code mvn -Dtest=SpringAiDoubaoIntentClassifierLiveIT test}
 */
@SpringBootTest(
        args = "help",
        properties = "logging.file.name=")
class SpringAiDoubaoIntentClassifierLiveIT {

    @Autowired
    private IntentClassifier classifier;

    @Test
    void classifiesRepresentativeWechatMessages() {
        IntentDecision weatherStatement = classifier.classify("今天天气好好啊");
        assertEquals(IntentType.TEXT_CHAT, weatherStatement.intent());

        IntentDecision travelStatement =
                classifier.classify("今天去杭州玩了，天气真好");
        assertEquals(IntentType.TEXT_CHAT, travelStatement.intent());

        IntentDecision weatherQuestion =
                classifier.classify("明天杭州天气怎么样");
        assertEquals(IntentType.WEATHER_QUERY, weatherQuestion.intent());
        assertTrue(weatherQuestion.city().contains("杭州"));
        assertEquals(
                LocalDate.now(WeatherService.WEATHER_ZONE).plusDays(1),
                weatherQuestion.targetDate());

        IntentDecision image =
                classifier.classify("帮我画一只戴着宇航员头盔的橘猫");
        assertEquals(IntentType.IMAGE_GENERATION, image.intent());
        assertTrue(image.requestText().contains("橘猫"));

        IntentDecision voice =
                classifier.classify("请用语音回答：李白是谁");
        assertEquals(IntentType.TEXT_CHAT, voice.intent());
        assertEquals(ReplyMode.VOICE, voice.replyMode());
        assertTrue(voice.requestText().contains("李白"));
    }
}
