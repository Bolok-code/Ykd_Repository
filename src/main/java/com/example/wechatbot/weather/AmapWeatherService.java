package com.example.wechatbot.weather;

import com.example.wechatbot.config.AppConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Service
public class AmapWeatherService {

    private static final Logger log = LoggerFactory.getLogger(AmapWeatherService.class);

    @Autowired
    private AppConfig appConfig;

    private OkHttpClient httpClient;
    private ObjectMapper objectMapper;
    private final Map<String, String> cityCodeCache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        this.objectMapper = new ObjectMapper();
        initCityCodes();
        log.info("高德天气服务初始化完成");
    }

    private void initCityCodes() {
        cityCodeCache.put("北京", "110000");
        cityCodeCache.put("上海", "310000");
        cityCodeCache.put("广州", "440100");
        cityCodeCache.put("深圳", "440300");
        cityCodeCache.put("杭州", "330100");
        cityCodeCache.put("成都", "510100");
        cityCodeCache.put("武汉", "420100");
        cityCodeCache.put("西安", "610100");
        cityCodeCache.put("南京", "320100");
        cityCodeCache.put("重庆", "500000");
        cityCodeCache.put("天津", "120000");
        cityCodeCache.put("苏州", "320500");
        cityCodeCache.put("长沙", "430100");
        cityCodeCache.put("郑州", "410100");
        cityCodeCache.put("厦门", "350200");
        cityCodeCache.put("青岛", "370200");
        cityCodeCache.put("大连", "210200");
        cityCodeCache.put("昆明", "530100");
        cityCodeCache.put("合肥", "340100");
        cityCodeCache.put("济南", "370100");
        cityCodeCache.put("福州", "350100");
        cityCodeCache.put("哈尔滨", "230100");
        cityCodeCache.put("沈阳", "210100");
        cityCodeCache.put("长春", "220100");
        cityCodeCache.put("海口", "460100");
        cityCodeCache.put("三亚", "460200");
    }

    public String queryWeather(String cityName) {
        log.info("查询天气: 城市={}", cityName);

        if (cityName == null || cityName.trim().isEmpty()) {
            return "请输入城市名\n\n示例：天气 北京";
        }

        cityName = cityName.trim();
        String adcode = getCityCode(cityName);
        if (adcode == null) {
            return "未找到城市 [" + cityName + "]\n\n请检查城市名是否正确";
        }

        try {
            String apiKey = appConfig.getAmap().getApiKey();
            String baseUrl = appConfig.getAmap().getBaseUrl();
            String url = String.format("%s/weather/weatherInfo?key=%s&city=%s&extensions=base&output=JSON",
                    baseUrl, apiKey, adcode);

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "天气服务请求失败，请稍后再试";
                }

                String body = response.body() != null ? response.body().string() : "";
                WeatherResponse weatherResponse = objectMapper.readValue(body, WeatherResponse.class);

                if (weatherResponse.isSuccess()) {
                    WeatherResponse.LiveWeather live = weatherResponse.getFirstLive();
                    if (live != null) {
                        return live.toFriendlyString();
                    }
                }
                return "查询失败: " + weatherResponse.getInfo();
            }
        } catch (Exception e) {
            log.error("查询天气异常", e);
            return "网络请求异常，请稍后再试";
        }
    }

    private String getCityCode(String cityName) {
        String code = cityCodeCache.get(cityName);
        if (code != null) {
            return code;
        }

        try {
            String apiKey = appConfig.getAmap().getApiKey();
            String baseUrl = appConfig.getAmap().getBaseUrl();
            String encodedCity = URLEncoder.encode(cityName, StandardCharsets.UTF_8);
            String url = String.format("%s/geocode/geo?key=%s&address=%s", baseUrl, apiKey, encodedCity);

            Request request = new Request.Builder().url(url).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    String body = response.body() != null ? response.body().string() : "";
                    JsonNode jsonNode = objectMapper.readTree(body);
                    JsonNode geocodes = jsonNode.path("geocodes");

                    if (geocodes.isArray() && geocodes.size() > 0) {
                        String adcode = geocodes.get(0).path("adcode").asText();
                        if (adcode != null && !adcode.isEmpty()) {
                            cityCodeCache.put(cityName, adcode);
                            return adcode;
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("地理编码查询失败: {}", cityName, e);
        }

        return null;
    }
}
