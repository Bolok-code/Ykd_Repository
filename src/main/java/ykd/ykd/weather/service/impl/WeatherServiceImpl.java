package ykd.ykd.weather.service.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.weather.api.dto.WeatherResponse;
import ykd.ykd.weather.service.WeatherService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 天气查询服务实现 —— 通过心知天气 API（HMAC-SHA1 签名认证）查询实时天气。
 *
 * <p>核心流程：参数校验 → 签名构建 → HTTP 调用 → 解析响应 → 返回结果</p>
 */
@Service
public class WeatherServiceImpl implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherServiceImpl.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Value("${weather.api.url}")
    private String apiUrl;

    @Value("${weather.api.public-key}")
    private String publicKey;

    @Value("${weather.api.private-key}")
    private String privateKey;

    @Value("${weather.api.ttl:1800}")
    private int ttl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .build();

    @Override
    public WeatherResponse getWeatherByCity(String city) {
        if (city == null || city.isBlank()) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "城市名不能为空");
        }
        city = city.trim();

        if (!city.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-·]+$")) {
            throw new BusinessException(ErrorCode.PARAM_ERROR, "城市名包含非法字符: " + city);
        }

        String url = buildSignedUrl(city);
        log.info("天气查询请求：城市 = {}", city);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            log.info("天气查询响应：status = {}", response.statusCode());

            if (response.statusCode() != 200) {
                throw new BusinessException(ErrorCode.API_ERROR,
                        "心知天气API返回异常状态码: " + response.statusCode());
            }

            return parseResponse(body, city);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("天气API调用异常：城市 = {}", city, e);
            throw new BusinessException(ErrorCode.NETWORK_ERROR, "天气API调用失败: " + e.getMessage());
        }
    }

    /**
     * 构建带 HMAC-SHA1 签名的完整请求 URL。
     *
     * <p>签名参数（ts、ttl、uid 按字母升序排列）用私钥做 HMAC-SHA1 → Base64 → URLEncode → sig。</p>
     */
    private String buildSignedUrl(String city) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String signatureParams = String.format("ts=%s&ttl=%d&uid=%s", ts, ttl, publicKey);
        String sig = urlEncode(hmacSha1Sign(signatureParams, privateKey));

        return String.format("%s?key=%s&location=%s&language=zh-Hans&unit=c&%s&sig=%s",
                apiUrl, publicKey, city, signatureParams, sig);
    }

    private String hmacSha1Sign(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            log.error("HMAC-SHA1签名失败", e);
            throw new BusinessException(ErrorCode.API_ERROR, "签名构建失败");
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /**
     * 解析心知天气 JSON 响应，提取实时天气信息。
     */
    private WeatherResponse parseResponse(String body, String city) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();

            if (root.has("status_code") && !root.get("status_code").getAsString().equals("200")) {
                String msg = root.has("status") ? root.get("status").getAsString() : "未知错误";
                throw new BusinessException(ErrorCode.API_ERROR, "查询失败 [" + city + "]: " + msg);
            }

            JsonArray results = root.getAsJsonArray("results");
            if (results == null || results.isEmpty()) {
                throw new BusinessException(ErrorCode.CITY_NOT_FOUND, "未查询到城市: " + city);
            }

            JsonObject result = results.get(0).getAsJsonObject();
            JsonObject location = result.getAsJsonObject("location");
            JsonObject now = result.getAsJsonObject("now");

            if (location == null || now == null) {
                throw new BusinessException(ErrorCode.ANALYSIS_ERROR, "天气数据不完整: " + city);
            }

            return WeatherResponse.builder()
                    .type("base")
                    .city(location.get("name").getAsString())
                    .weather(now.get("text").getAsString())
                    .temperature(now.get("temperature").getAsString())
                    .humidity(now.has("humidity") ? now.get("humidity").getAsString() : null)
                    .windDirection(now.has("wind_direction") ? now.get("wind_direction").getAsString() : null)
                    .windPower(now.has("wind_scale") ? now.get("wind_scale").getAsString() : null)
                    .reportTime(result.has("last_update") ? result.get("last_update").getAsString() : null)
                    .build();

        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("解析天气响应失败：body = {}", body, e);
            throw new BusinessException(ErrorCode.ANALYSIS_ERROR, "解析天气数据失败");
        }
    }
}