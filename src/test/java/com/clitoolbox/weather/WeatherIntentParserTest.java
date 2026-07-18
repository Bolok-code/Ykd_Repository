package com.clitoolbox.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class WeatherIntentParserTest {
    private final WeatherIntentParser parser = new WeatherIntentParser(
            Clock.fixed(
                    Instant.parse("2026-07-18T04:00:00Z"),
                    WeatherIntentParser.WEATHER_ZONE));

    @Test
    void parsesRealtimeWeatherAndCommonQueryPrefixes() {
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "查询杭州天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "帮我查一下杭州天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "杭州的天气怎么样");
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "帮我看看杭州现在的温度");
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "杭州天气好吗");
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "杭州天气冷不冷");
        assertIntent("杭州", LocalDate.of(2026, 7, 18), "杭州气温多少");
    }

    @Test
    void parsesRelativeDatesBeforeOrAfterCity() {
        assertIntent("杭州", LocalDate.of(2026, 7, 19), "明天杭州天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 19), "杭州明天天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 20), "查询杭州后天天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 21), "杭州大后天的天气怎么样");
    }

    @Test
    void parsesChineseAndNumericCalendarDates() {
        assertIntent("杭州", LocalDate.of(2026, 7, 20), "7月20日杭州天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 20), "杭州7月20号的天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 20), "杭州2026年7月20日天气");
        assertIntent("杭州", LocalDate.of(2026, 7, 20), "查询杭州2026-07-20天气");
    }

    @Test
    void choosesNextOccurrenceWhenYearIsOmitted() {
        assertIntent("杭州", LocalDate.of(2027, 1, 5), "杭州1月5日天气");
    }

    @Test
    void rejectsInvalidDateAndIgnoresNonWeatherMessages() {
        CliException error = assertThrows(
                CliException.class,
                () -> parser.parse("杭州2月30日天气"));
        assertEquals(ErrorCode.INVALID_INPUT, error.getErrorCode());
        assertNull(parser.parse("天气怎么样"));
        assertNull(parser.parse("你好"));
        assertNull(parser.parse(null));
    }

    @Test
    void ignoresDeclarativeSentencesThatOnlyMentionWeather() {
        assertNull(parser.parse("今天天气好好啊"));
        assertNull(parser.parse("杭州今天天气好好啊"));
        assertNull(parser.parse("今天去杭州玩了，天气真好"));
        assertNull(parser.parse("今天去杭州玩了天气真好"));
        assertNull(parser.parse("杭州天气真好"));
        assertNull(parser.parse("杭州天气晴朗"));
        assertNull(parser.parse("我很喜欢杭州的天气"));
        assertNull(parser.parse("昨天杭州天气不错"));
        assertNull(parser.parse("今天去杭州玩了，天气真好吗"));
    }

    private void assertIntent(String city, LocalDate date, String text) {
        WeatherIntent intent = parser.parse(text);
        assertEquals(city, intent.city());
        assertEquals(date, intent.targetDate());
    }
}
