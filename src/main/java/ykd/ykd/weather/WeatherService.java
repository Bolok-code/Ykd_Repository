package ykd.ykd.weather;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.SystemException;

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
 * 天气查询服务 —— 通过心知天气 API（HMAC-SHA1 签名认证）查询实时天气。
 *
 * <p>核心流程：参数校验 → 签名构建 → HTTP 调用 → 解析响应 → 返回结果</p>
 * <p>所有边界情况（空城市名、无效城市名、网络超时等）均已覆盖。</p>
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String apiUrl;
    private final String publicKey;
    private final String privateKey;
    private final int ttl;

    private final HttpClient httpClient;
    private final Gson gson;

    public WeatherService(
            @Value("${weather.api.url}") String apiUrl,
            @Value("${weather.api.public-key}") String publicKey,
            @Value("${weather.api.private-key}") String privateKey,
            @Value("${weather.api.ttl:1800}") int ttl) {
        this.apiUrl = apiUrl;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
        this.ttl = ttl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
        this.gson = new Gson();
    }

    // ==================== 公开接口 ====================

    /**
     * 按城市名查询实时天气。
     *
     * @param city 城市名（中文或英文）
     * @return 天气响应对象
     * @throws BusinessException 参数为空、城市无效等业务错误
     * @throws SystemException   HTTP 调用失败、解析失败等系统错误
     */
    public WeatherResponse queryWeather(String city) {
        // 边界情况 1：空城市名
        if (city == null || city.isBlank()) {
            log.warn("天气查询请求被拒绝：城市名为空");
            throw BusinessException.invalidParam("city", "城市名不能为空");
        }

        log.info("天气查询请求：城市 = {}", city);

        // 边界情况 2：城市名含非法字符
        if (!city.matches("^[\\u4e00-\\u9fa5a-zA-Z0-9\\s\\-·]+$")) {
            log.warn("天气查询请求被拒绝：城市名包含非法字符 = {}", city);
            throw BusinessException.invalidParam("city", "城市名包含非法字符: " + city);
        }

        // 构建带签名的请求 URL
        String url = buildSignedUrl(city);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            log.debug("发送 GET 请求...");

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            String body = response.body();
            log.info("天气查询响应：status = {}, body = {}",
                    response.statusCode(),
                    body.length() > 200 ? body.substring(0, 200) : body);

            // 边界情况 3：HTTP 状态码异常
            if (response.statusCode() != 200) {
                String apiMsg = tryParseApiError(body);
                if (apiMsg != null) {
                    log.error("天气API返回业务错误：status = {}, msg = {}",
                            response.statusCode(), apiMsg);
                    throw BusinessException.invalidParam("天气API",
                            String.format("查询失败 [%s]: %s", city, apiMsg));
                }
                throw SystemException.httpError(
                        "天气API返回异常状态码: " + response.statusCode(), null);
            }

            return parseAndValidate(body, city);

        } catch (BusinessException | SystemException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            log.error("天气API调用超时：城市 = {}", city, e);
            throw SystemException.httpError("天气API请求超时（" + city + "）", e);
        } catch (java.net.ConnectException e) {
            log.error("天气API连接失败：城市 = {}", city, e);
            throw SystemException.httpError("天气API连接失败，请检查网络", e);
        } catch (java.io.IOException e) {
            log.error("天气API IO异常：城市 = {}", city, e);
            throw SystemException.ioError("天气API网络错误（" + city + "）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SystemException.unknown(e);
        } catch (Exception e) {
            log.error("天气API未知异常：城市 = {}", city, e);
            throw SystemException.unknown(e);
        }
    }

    // ==================== 签名构建 ====================

    /**
     * 构建带 HMAC-SHA1 签名的完整请求 URL。
     *
     * <p>签名参数（ts、ttl、uid 按字母升序排列）用私钥做 HMAC-SHA1 → Base64 → URLEncode → sig。
     * 业务参数（location、language、unit 等）不参与签名。</p>
     */
    private String buildSignedUrl(String city) {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        String signatureParams = String.format("ts=%s&ttl=%d&uid=%s", ts, ttl, publicKey);
        String sig = urlEncode(hmacSha1Sign(signatureParams, privateKey));

        log.debug("签名参数: {}, sig: {}", signatureParams, sig);

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
            throw SystemException.unknown(e);
        }
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    // ==================== 响应解析 ====================

    private WeatherResponse parseAndValidate(String body, String city) {
        WeatherResponse wr;
        try {
            wr = gson.fromJson(body, WeatherResponse.class);
        } catch (Exception e) {
            log.error("天气响应JSON解析失败：body = {}", body, e);
            throw SystemException.unknown(e);
        }

        // 边界情况 4：API 返回结果为空
        if (wr == null || wr.results == null || wr.results.isEmpty()) {
            try {
                JsonObject json = JsonParser.parseString(body).getAsJsonObject();
                if (json.has("status_code")
                        && !json.get("status_code").getAsString().equals("200")) {
                    String msg = json.has("status")
                            ? json.get("status").getAsString() : "未知错误";
                    log.warn("天气API业务错误：城市 = {}, code = {}, msg = {}",
                            city, json.get("status_code").getAsString(), msg);
                    throw BusinessException.notFound("城市", city + " — " + msg);
                }
            } catch (BusinessException be) {
                throw be;
            } catch (Exception ignored) {}
            log.warn("天气查询无结果：城市 = {}", city);
            throw BusinessException.notFound("城市", city + " — 请检查城市名拼写");
        }

        WeatherResponse.Result r = wr.results.get(0);
        if (r.location == null || r.now == null) {
            log.warn("天气数据不完整：城市 = {}", city);
            throw BusinessException.notFound("城市", city + " — 数据不完整");
        }

        log.info("天气查询成功：城市 = {}, 天气 = {}, 温度 = {}℃",
                r.location.name, r.now.text, r.now.temperature);
        return wr;
    }

    private String tryParseApiError(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("status")) return json.get("status").getAsString();
        } catch (Exception ignored) {}
        return null;
    }
}
