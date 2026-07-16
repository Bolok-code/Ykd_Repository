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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.logging.Logger;

public class WeatherService {
    private static final Logger LOG = Logger.getLogger(WeatherService.class.getName());
    private static final String API_KEY_ENV = "WEATHER_API_KEY";
    private static final String NOW_URL = "https://api.seniverse.com/v3/weather/now.json";
    private static final String DAILY_URL = "https://api.seniverse.com/v3/weather/daily.json";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public WeatherService() {
        this.httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
        this.objectMapper = new ObjectMapper();
        this.apiKey = System.getenv(API_KEY_ENV);
        if (apiKey == null || apiKey.isBlank()) LOG.warning("环境变量 " + API_KEY_ENV + " 未设置");
    }

    public WeatherResult query(String city) {
        if (city == null || city.isBlank()) throw new CliException(ErrorCode.INVALID_INPUT, "城市名不能为空。");
        if (apiKey == null || apiKey.isBlank()) throw new CliException(ErrorCode.CONFIG_ERROR,
                "天气 API Key 未设置。\n  请注册心知天气 (https://www.seniverse.com/) 获取免费 Key，\n  然后设置环境变量: set WEATHER_API_KEY=你的Key");

        LOG.info("查询天气 - 城市: " + city);
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
                LOG.fine("每日预报数据不可用: " + e.getMessage());
            }

            WeatherResult wr = new WeatherResult(cityName, weatherDesc, temp, feelsLike,
                    humidity, windSpeed, updateTime, highTemp, lowTemp);
            LOG.info("天气结果 - " + cityName + ": " + weatherDesc + ", " + temp + "C");
            return wr;

        } catch (CliException e) { LOG.severe("天气查询失败 - " + e.getUserMessage()); throw e;
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

    private String httpGet(String url) throws Exception {
        HttpResponse<String> resp = httpClient.send(
                HttpRequest.newBuilder().uri(URI.create(url)).timeout(TIMEOUT).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private void checkError(JsonNode root) {
        JsonNode sc = root.get("status_code");
        if (sc != null) throw new CliException(ErrorCode.NETWORK_ERROR,
                "心知天气 API 返回错误: " + root.get("status").asText("未知"));
    }

    private String safeText(JsonNode n, String f, String d) { JsonNode v = n.get(f); return (v != null && !v.isNull()) ? v.asText() : d; }
    private double safeDouble(JsonNode n, String f, double d) { JsonNode v = n.get(f); if (v == null || v.isNull()) return d; if (v.isNumber()) return v.asDouble(); if (v.isTextual()) { try { return Double.parseDouble(v.asText()); } catch (NumberFormatException e) { return d; }} return d; }
    private int safeInt(JsonNode n, String f, int d) { JsonNode v = n.get(f); if (v == null || v.isNull()) return d; if (v.isNumber()) return v.asInt(); if (v.isTextual()) { try { return Integer.parseInt(v.asText()); } catch (NumberFormatException e) { return d; }} return d; }
    private long parseTime(String s) { if (s == null || s.isBlank()) return System.currentTimeMillis(); try { return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant().toEpochMilli(); } catch (Exception e) { return System.currentTimeMillis(); } }
}
