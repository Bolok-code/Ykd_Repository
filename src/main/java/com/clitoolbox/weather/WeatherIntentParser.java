package com.clitoolbox.weather;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 从“明天杭州天气”“杭州7月20日天气”等自然语言中提取城市和日期。
 */
@Component
public final class WeatherIntentParser {
    public static final ZoneId WEATHER_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Pattern WEATHER_KEYWORD_PATTERN =
            Pattern.compile("(天气预报|天气|气温|多少度|温度)");
    private static final Pattern CHINESE_DATE_PATTERN = Pattern.compile(
            "(?:(\\d{4})\\s*年\\s*)?(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*(?:日|号)");
    private static final Pattern NUMERIC_DATE_PATTERN = Pattern.compile(
            "(?<!\\d)(?:(\\d{4})[-/.])?(\\d{1,2})[-/.](\\d{1,2})(?!\\d)");
    private static final Pattern RELATIVE_DATE_PATTERN =
            Pattern.compile("(大后天|后天|明天|今天|今日|现在|当前)");
    private static final Pattern QUERY_PREFIX_PATTERN = Pattern.compile(
            "^(?:请问一下|请问|请|麻烦|劳驾|帮我|帮忙|给我|告诉我|"
                    + "我想要?|想要?|查询一下|查一下|查询|查查|查|看看|看一下|了解一下)\\s*");
    private static final Pattern LEADING_PARTICLE_PATTERN =
            Pattern.compile("^(?:一下|的)\\s*");
    private static final Pattern TRAILING_PHRASE_PATTERN = Pattern.compile(
            "\\s*(?:怎么样|如何|是什么情况|什么情况|会怎样|情况|一下|呢|吗|呀|啊|吧)\\s*$");
    private static final Pattern TRAILING_PARTICLE_PATTERN =
            Pattern.compile("\\s*的\\s*$");
    private static final Pattern EDGE_PUNCTUATION_PATTERN =
            Pattern.compile("^[\\s,，。！？?：:；;]+|[\\s,，。！？?：:；;]+$");

    private final Clock clock;

    public WeatherIntentParser() {
        this(Clock.system(WEATHER_ZONE));
    }

    WeatherIntentParser(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return 识别到天气意图时返回查询条件；不是天气消息或缺少城市时返回 {@code null}
     */
    public WeatherIntent parse(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized = text.trim();
        if (!WEATHER_KEYWORD_PATTERN.matcher(normalized).find()) {
            return null;
        }

        LocalDate today = LocalDate.now(clock);
        ParsedDate parsedDate = parseDate(normalized, today);
        String city = removeRange(normalized, parsedDate.start(), parsedDate.end());
        city = WEATHER_KEYWORD_PATTERN.matcher(city).replaceAll(" ");
        city = cleanCity(city);
        return city.isEmpty() ? null : new WeatherIntent(city, parsedDate.date());
    }

    private ParsedDate parseDate(String text, LocalDate today) {
        Matcher chineseDate = CHINESE_DATE_PATTERN.matcher(text);
        if (chineseDate.find()) {
            LocalDate date = resolveExplicitDate(
                    chineseDate.group(1),
                    chineseDate.group(2),
                    chineseDate.group(3),
                    today);
            return new ParsedDate(date, chineseDate.start(), chineseDate.end());
        }

        Matcher numericDate = NUMERIC_DATE_PATTERN.matcher(text);
        if (numericDate.find()) {
            LocalDate date = resolveExplicitDate(
                    numericDate.group(1),
                    numericDate.group(2),
                    numericDate.group(3),
                    today);
            return new ParsedDate(date, numericDate.start(), numericDate.end());
        }

        Matcher relativeDate = RELATIVE_DATE_PATTERN.matcher(text);
        if (relativeDate.find()) {
            int daysToAdd = switch (relativeDate.group(1)) {
                case "明天" -> 1;
                case "后天" -> 2;
                case "大后天" -> 3;
                default -> 0;
            };
            return new ParsedDate(
                    today.plusDays(daysToAdd),
                    relativeDate.start(),
                    relativeDate.end());
        }

        return new ParsedDate(today, -1, -1);
    }

    private LocalDate resolveExplicitDate(
            String yearText,
            String monthText,
            String dayText,
            LocalDate today) {
        int month = Integer.parseInt(monthText);
        int day = Integer.parseInt(dayText);

        if (yearText != null) {
            return createDate(Integer.parseInt(yearText), month, day);
        }

        // 未写年份时选择最近一个尚未过去的同月同日，并兼容 2 月 29 日。
        for (int year = today.getYear(); year <= today.getYear() + 8; year++) {
            try {
                LocalDate candidate = LocalDate.of(year, month, day);
                if (!candidate.isBefore(today)) {
                    return candidate;
                }
            } catch (DateTimeException ignored) {
                // 继续尝试下一年，例如非闰年的 2 月 29 日。
            }
        }
        throw invalidDate();
    }

    private LocalDate createDate(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException e) {
            throw invalidDate();
        }
    }

    private CliException invalidDate() {
        return new CliException(
                ErrorCode.INVALID_INPUT,
                "天气查询日期不正确，请使用“明天”或“7月20日”这样的日期。");
    }

    private String removeRange(String text, int start, int end) {
        if (start < 0) {
            return text;
        }
        return text.substring(0, start) + " " + text.substring(end);
    }

    private String cleanCity(String value) {
        String city = EDGE_PUNCTUATION_PATTERN.matcher(value).replaceAll("").trim();
        String previous;
        do {
            previous = city;
            city = QUERY_PREFIX_PATTERN.matcher(city).replaceFirst("").trim();
            city = LEADING_PARTICLE_PATTERN.matcher(city).replaceFirst("").trim();
            city = TRAILING_PHRASE_PATTERN.matcher(city).replaceFirst("").trim();
            city = TRAILING_PARTICLE_PATTERN.matcher(city).replaceFirst("").trim();
            city = EDGE_PUNCTUATION_PATTERN.matcher(city).replaceAll("").trim();
        } while (!city.isEmpty() && !city.equals(previous));
        return city;
    }

    private record ParsedDate(LocalDate date, int start, int end) {
    }
}
