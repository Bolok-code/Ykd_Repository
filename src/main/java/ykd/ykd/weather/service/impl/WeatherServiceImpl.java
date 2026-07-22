package ykd.ykd.weather.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;


import ykd.ykd.weather.api.dto.ForecastDay;

import ykd.ykd.weather.api.dto.WeatherResponse;
import ykd.ykd.weather.service.WeatherService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;



@Service
public class WeatherServiceImpl implements WeatherService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public WeatherServiceImpl(RestTemplate restTemplate, ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }// Jackson 自带，无需配置
    @Value("${gaode.key}")
    private String apiKey;

    private static final String GAODE_URL =
            "https://restapi.amap.com/v3/weather/weatherInfo?key={key}&city={city}&extensions={type}";
    private static final String DISTRICT_URL =
            "https://restapi.amap.com/v3/config/district?key={key}&keywords={keywords}&subdistrict=0";

    @Override
    public WeatherResponse getWeatherByCity(String city, String type) {
        if (city == null || city.isBlank()) {
            throw new BusinessException(ErrorCode.CITY_NOT_FOUND, "城市名不能为空");
        }
        city = city.trim();

        String adcode = city.matches("\\d+") ? city : resolveCityCode(city).get("adcode");
        if (adcode == null) {
            throw new BusinessException(ErrorCode.CITY_NOT_FOUND, "未找到城市编码: " + city);
        }

        try {
            String json = restTemplate.getForObject(GAODE_URL, String.class, apiKey, adcode, type);
            JsonNode jsonNode = objectMapper.readTree(json);
            if (!"1".equals(jsonNode.path("status").asText())) {
                throw new BusinessException(ErrorCode.API_ERROR, "天气查询失败: ");
            }
            return "base".equals(type) ? parseLive(jsonNode) : parseForecast(jsonNode);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, e.getMessage());
        }


    }

    @Override
    public String getWeatherText(String city) {
        WeatherResponse w = getWeatherByCity(city, "base");
        return String.format("%s %s°C %s 湿度%s%% 风力%s级",
                w.weather(), w.temperature(), w.windDirection(), w.humidity(), w.windPower());
    }

    private WeatherResponse parseLive(JsonNode root) {
        JsonNode live = root.path("lives").get(0);
        return new WeatherResponse(
                "base", live.path("province").asText(), live.path("city").asText(),
                live.path("weather").asText(), live.path("temperature").asText(), live.path("humidity").asText(),
                live.path("winddirection").asText(), live.path("windpower").asText(), live.path("reporttime").asText(),
                null);
    }

    private WeatherResponse parseForecast(JsonNode root) {
        JsonNode forecast = root.path("forecasts").get(0);

        List<ForecastDay> list = new ArrayList<>();
        for (JsonNode cast : forecast.path("casts")) {
            list.add(new ForecastDay(
                    cast.path("date").asText(), cast.path("week").asText(),
                    cast.path("dayweather").asText(), cast.path("nightweather").asText(),
                    cast.path("daytemp").asText(), cast.path("nighttemp").asText(),
                    cast.path("daywind").asText(), cast.path("nightwind").asText(),
                    cast.path("daypower").asText(), cast.path("nightpower").asText()));
        }
        return new WeatherResponse(
                "all", forecast.path("province").asText(), forecast.path("city").asText(),
                null, null, null, null, null, forecast.path("reporttime").asText(), list);
    }


    private Map<String, String> resolveCityCode(String keyword) {
        try {
            String json = restTemplate.getForObject(DISTRICT_URL, String.class, apiKey, keyword);
            JsonNode root = objectMapper.readTree(json);
            JsonNode district = root.path("districts").get(0);
            if (district == null || district.path("adcode").asText().isEmpty()) {
                throw new BusinessException(ErrorCode.CITY_NOT_FOUND, keyword);
            }
            Map<String, String> result = new LinkedHashMap<>();
            result.put("city", district.path("name").asText());
            result.put("adcode", district.path("adcode").asText());
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.NETWORK_ERROR, e.getMessage());
        }
    }
}


