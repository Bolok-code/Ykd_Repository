package ykd.ykd.weather.service.impl;

import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class WeatherServiceImpl implements WeatherService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;// Jackson 自带，无需配置
    @Value("${gaode.key}")
    private String apiKey;

    private static final String GAODE_URL =
            "https://restapi.amap.com/v3/weather/weatherInfo?key={key}&city={city}&extensions={type}";
    private static final String DISTRICT_URL =
            "https://restapi.amap.com/v3/config/district?key={key}&keywords={keywords}&subdistrict=0";

    /**
     * 通过高德 API 查询指定城市的天气数据。
     *
     * <p>支持中文城市名和 adcode 两种输入格式，中文名会先调用行政区划 API 转换为 adcode。
     * 根据 {@code type} 参数解析返回实时天气或未来预报。</p>
     *
     * @param city 中文城市名（如 北京、上海）或数字 adcode
     * @param type 查询类型，{@code "base"} 查询实时天气，{@code "all"} 查询未来几天预报
     * @return 天气响应对象，type=base 时包含 lives 数据，type=all 时包含 forecasts 列表
     * @throws BusinessException 城市名为空、城市未找到、API 调用失败或网络异常时抛出
     */
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

    /**
     * 将天气查询结果格式化为可读文本。
     *
     * <p>根据 {@code type} 参数区分实时天气和预报的文本拼接逻辑。</p>
     *
     * @param city 城市名称或 adcode
     * @param type 查询类型，{@code "base"} 返回实时天气，{@code "all"} 返回未来几天预报
     * @return 格式化后的天气文本，无预报数据时返回 {@code "暂无预报数据"}
     */
    @Override
    public String getWeatherText(String city, String type) {
        WeatherResponse w = getWeatherByCity(city, type);
        if ("all".equals(type)) {
            if (w.forecasts() == null || w.forecasts().isEmpty()) {
                return "暂无预报数据";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(w.city()).append("未来天气：\n");
            for (ForecastDay day : w.forecasts()) {
                sb.append(String.format("%s(%s) 白天%s %s°C 夜间%s %s°C\n",
                        day.date(), day.week(),
                        day.dayWeather(), day.dayTemp(),
                        day.nightWeather(), day.nightTemp()));
            }
            return sb.toString().trim();
        }
        return String.format("%s %s°C %s 湿度%s%% 风力%s级",
                w.weather(), w.temperature(), w.windDirection(), w.humidity(), w.windPower());
    }

    private WeatherResponse parseLive(JsonNode root) {
        JsonNode live = root.path("lives").get(0);
        return WeatherResponse.builder()
                .type("base")
                .province(live.path("province").asText())
                .city(live.path("city").asText())
                .weather(live.path("weather").asText())
                .temperature(live.path("temperature").asText())
                .humidity(live.path("humidity").asText())
                .windDirection(live.path("winddirection").asText())
                .windPower(live.path("windpower").asText())
                .reportTime(live.path("reporttime").asText())
                .build();
    }

    private WeatherResponse parseForecast(JsonNode root) {
        JsonNode forecast = root.path("forecasts").get(0);

        List<ForecastDay> list = new ArrayList<>();
        for (JsonNode cast : forecast.path("casts")) {
            list.add(ForecastDay.builder()
                    .date(cast.path("date").asText())
                    .week(cast.path("week").asText())
                    .dayWeather(cast.path("dayweather").asText())
                    .nightWeather(cast.path("nightweather").asText())
                    .dayTemp(cast.path("daytemp").asText())
                    .nightTemp(cast.path("nighttemp").asText())
                    .dayWind(cast.path("daywind").asText())
                    .nightWind(cast.path("nightwind").asText())
                    .dayPower(cast.path("daypower").asText())
                    .nightPower(cast.path("nightpower").asText())
                    .build());
        }
        return WeatherResponse.builder()
                .type("all")
                .province(forecast.path("province").asText())
                .city(forecast.path("city").asText())
                .reportTime(forecast.path("reporttime").asText())
                .forecasts(list)
                .build();
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


