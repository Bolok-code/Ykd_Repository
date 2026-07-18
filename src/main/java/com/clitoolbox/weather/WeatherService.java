package com.clitoolbox.weather;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@Lazy
public class WeatherService {
    private static final Logger LOG = LoggerFactory.getLogger(WeatherService.class);
    private static final String API_KEY_ENV = "WEATHER_API_KEY";
    private static final String NOW_URL = "https://api.seniverse.com/v3/weather/now.json";
    private static final String DAILY_URL = "https://api.seniverse.com/v3/weather/daily.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_FORECAST_OFFSET_DAYS = 14;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final Clock clock;

    @Autowired
    public WeatherService(
            ObjectMapper objectMapper,
            @Value("${app.weather.api-key:}") String apiKey) {
        this(
                objectMapper,
                apiKey,
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build(),
                Clock.system(WeatherIntentParser.WEATHER_ZONE));
    }

    WeatherService(
            ObjectMapper objectMapper,
            String apiKey,
            HttpClient httpClient,
            Clock clock) {
        this.objectMapper = objectMapper;
        this.apiKey = apiKey;
        this.httpClient = httpClient;
        this.clock = clock;
        if (apiKey == null || apiKey.isBlank()) LOG.warn("环境变量 {} 未设置", API_KEY_ENV);
    }

    public WeatherResult query(String city) {
        validateCityAndApiKey(city);

        LOG.info("查询天气 - 城市: {}", city);
        try {
            String enc = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String q = "?key=" + apiKey + "&location=" + enc + "&language=zh-Hans&unit=c";

            // ---- 1. 获取实时天气 (now.json) ----
            String nowBody = httpGet(NOW_URL + q);
            JsonNode nowRoot = objectMapper.readTree(nowBody);
            checkError(nowRoot);
            JsonNode nowResult = nowRoot.get("results").get(0);
            JsonNode now = nowResult.get("now");

            String cityName = nowResult.get("location").get("name").asText(city);
            String weatherDesc = safeText(now, "text", "未知");
            double temp = safeDouble(now, "temperature", 0);
            double feelsLike = safeDouble(now, "feels_like", temp);
            double humidityD = safeDouble(now, "humidity", -1);
            double windD = safeDouble(now, "wind_speed", -1);
            long updateTime = parseTime(nowResult.get("last_update").asText(null));

            // ---- 2. 获取每日预报 (daily.json) 补充高低温/湿度/风速 ----
            double highTemp = temp, lowTemp = temp;
            int humidity = (humidityD >= 0) ? (int)humidityD : 0;
            double windSpeed = (windD >= 0) ? windD : 0;

            try {
                String dailyBody = httpGet(DAILY_URL + q + "&start=0&days=1");
                JsonNode dailyRoot = objectMapper.readTree(dailyBody);
                checkError(dailyRoot);
                JsonNode daily = dailyRoot.get("results").get(0).get("daily").get(0);
                highTemp = safeDouble(daily, "high", temp);
                lowTemp = safeDouble(daily, "low", temp);
                int dh = safeInt(daily, "humidity", -1);
                if (dh > 0) humidity = dh;
                double dw = safeDouble(daily, "wind_speed", -1);
                if (dw > 0) windSpeed = dw;
            } catch (Exception e) {
                LOG.debug("每日预报数据不可用: {}", e.getMessage());
            }

            WeatherResult wr = new WeatherResult(cityName, weatherDesc, temp, feelsLike,
                    humidity, windSpeed, updateTime, highTemp, lowTemp);
            LOG.info("天气结果 - {}: {}, {}C", cityName, weatherDesc, temp);
            return wr;

        } catch (CliException e) { LOG.error("天气查询失败 - {}", e.getUserMessage()); throw e;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CliException(ErrorCode.NETWORK_ERROR, "请求被中断。");
        } catch (IOException e) {
            String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
            if (msg.contains("timeout") || msg.contains("timed out"))
                throw new CliException(ErrorCode.NETWORK_ERROR, "天气服务连接超时，请稍后重试。");
            throw new CliException(ErrorCode.NETWORK_ERROR, "网络请求失败，请检查网络连接。");
        } catch (Exception e) {
            throw new CliException(ErrorCode.UNKNOWN, "天气查询失败: " + e.getMessage());
        }
    }

    public WeatherForecastResult forecast(String city, LocalDate targetDate) {
        validateCityAndApiKey(city);
        if (targetDate == null) {
            throw new CliException(ErrorCode.INVALID_INPUT, "天气预报日期不能为空。");
        }

        LocalDate today = LocalDate.now(clock);
        long offsetDays = ChronoUnit.DAYS.between(today, targetDate);
        if (offsetDays < 0) {
            throw new CliException(ErrorCode.INVALID_INPUT, "暂不支持查询已经过去的天气。");
        }
        if (offsetDays > MAX_FORECAST_OFFSET_DAYS) {
            throw new CliException(
                    ErrorCode.INVALID_INPUT,
                    "当前只能查询未来 15 天内的天气预报，且实际天数取决于天气 API 套餐。");
        }

        LOG.info("查询天气预报 - 城市: {}, 日期: {}", city, targetDate);
        try {
            String enc = URLEncoder.encode(city, StandardCharsets.UTF_8);
            String q = "?key=" + apiKey + "&location=" + enc + "&language=zh-Hans&unit=c";
            int requestedDays = Math.toIntExact(offsetDays) + 1;
            String dailyBody = httpGet(
                    DAILY_URL + q + "&start=0&days=" + requestedDays);
            JsonNode root = objectMapper.readTree(dailyBody);
            checkError(root);

            JsonNode result = root.path("results").path(0);
            JsonNode matchedForecast = null;
            for (JsonNode daily : result.path("daily")) {
                if (targetDate.toString().equals(safeText(daily, "date", ""))) {
                    matchedForecast = daily;
                    break;
                }
            }
            if (matchedForecast == null) {
                throw new CliException(
                        ErrorCode.INVALID_INPUT,
                        "天气服务没有返回 " + targetDate
                                + " 的预报，当前套餐可能不支持这么远的日期。");
            }

            String cityName = result.path("location").path("name").asText(city);
            WeatherForecastResult forecast = new WeatherForecastResult(
                    cityName,
                    targetDate,
                    safeText(matchedForecast, "text_day", "未知"),
                    safeText(matchedForecast, "text_night", "未知"),
                    safeDouble(matchedForecast, "high", 0),
                    safeDouble(matchedForecast, "low", 0),
                    safeInt(matchedForecast, "humidity", 0),
                    safeDouble(matchedForecast, "wind_speed", 0),
                    parseTime(result.path("last_update").asText(null)));
            LOG.info(
                    "天气预报结果 - {} {}: {} / {}",
                    cityName,
                    targetDate,
                    forecast.dayDescription(),
                    forecast.nightDescription());
            return forecast;
        } catch (CliException e) {
            LOG.error("天气预报查询失败 - {}", e.getUserMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CliException(ErrorCode.NETWORK_ERROR, "请求被中断。");
        } catch (IOException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("timeout") || message.contains("timed out")) {
                throw new CliException(ErrorCode.NETWORK_ERROR, "天气服务连接超时，请稍后重试。");
            }
            throw new CliException(ErrorCode.NETWORK_ERROR, "网络请求失败，请检查网络连接。");
        } catch (Exception e) {
            throw new CliException(ErrorCode.UNKNOWN, "天气预报查询失败: " + e.getMessage());
        }
    }

    private void validateCityAndApiKey(String city) {
        if (city == null || city.isBlank()) {
            throw new CliException(ErrorCode.INVALID_INPUT, "城市名不能为空。");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new CliException(
                    ErrorCode.CONFIG_ERROR,
                    "天气 API Key 未设置。\n  请注册心知天气 (https://www.seniverse.com/) 获取免费 Key，\n"
                            + "  然后设置环境变量: set WEATHER_API_KEY=你的Key");
        }
    }

    private String httpGet(String url) throws Exception {
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private void checkError(JsonNode root) {
        JsonNode sc = root.get("status_code");
        if (sc != null) {
            String code = sc.asText();
            String status = root.get("status").asText("未知错误");
            String msg = translateApiError(code, status);
            ErrorCode type = code.contains("KEY") ? ErrorCode.CONFIG_ERROR : ErrorCode.INVALID_INPUT;
            throw new CliException(type, msg);
        }
    }

    private String translateApiError(String code, String status) {
        String s = status.toLowerCase();
        if (code.contains("005") || s.contains("key")) return "天气 API Key 无效，请检查 WEATHER_API_KEY 是否正确。";
        if (code.contains("010") || s.contains("location")) return "未找到该城市，请检查城市名是否正确。";
        if (code.contains("020") || s.contains("ip")) return "API 密钥未绑定当前 IP 地址，请在管理后台添加白名单。";
        if (code.contains("030") || s.contains("limit")) return "今日 API 调用次数已用尽，请明天再试或升级套餐。";
        return "天气查询服务异常: " + status + " (代码: " + code + ")";
    }

    private String safeText(JsonNode n, String f, String d) { JsonNode v = n.get(f); return (v != null && !v.isNull()) ? v.asText() : d; }
    private double safeDouble(JsonNode n, String f, double d) { JsonNode v = n.get(f); if (v == null || v.isNull()) return d; if (v.isNumber()) return v.asDouble(); if (v.isTextual()) { try { return Double.parseDouble(v.asText()); } catch (NumberFormatException e) { return d; }} return d; }
    private int safeInt(JsonNode n, String f, int d) { JsonNode v = n.get(f); if (v == null || v.isNull()) return d; if (v.isNumber()) return v.asInt(); if (v.isTextual()) { try { return Integer.parseInt(v.asText()); } catch (NumberFormatException e) { return d; }} return d; }
    private long parseTime(String s) { if (s == null || s.isBlank()) return System.currentTimeMillis(); try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli(); } catch (Exception e) { return System.currentTimeMillis(); } }
}
