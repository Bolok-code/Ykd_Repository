package ykd.ykd.ai.service.impl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ykd.ykd.ai.client.DeepSeekClient;
import ykd.ykd.ai.service.AiWeatherService;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.weather.api.dto.WeatherResponse;
import ykd.ykd.weather.service.WeatherService;

@Service
public class AiWeatherServiceImpl implements AiWeatherService {

    private static final Logger log = LoggerFactory.getLogger(AiWeatherServiceImpl.class);

    /**
     * 系统提示词：天气助手角色设定
     */
    private static final String WEATHER_PROMPT =
            "你是一个天气助手。用户会告诉你一个城市和对应的天气数据，" +
            "请你用亲切、拟人化的语气，结合天气信息给用户贴心的回复和建议。" +
            "回复要简短自然，像朋友聊天一样，不要太正式。" +
            "可以加入穿衣建议、出行提醒、心情相关的表达。" +
            "不要罗列数据，要融入到自然语言中。";

    /**
     * 系统提示词：从用户消息中提取城市名
     */
    private static final String EXTRACT_CITY_PROMPT =
            "你是一个城市名提取助手。从用户的消息中提取城市名。" +
            "只回复城市名，不要任何其他文字。" +
            "如果用户没有提到任何城市，回复：无";

    private final WeatherService weatherService;
    private final DeepSeekClient deepSeekClient;

    public AiWeatherServiceImpl(WeatherService weatherService, DeepSeekClient deepSeekClient) {
        this.weatherService = weatherService;
        this.deepSeekClient = deepSeekClient;
    }

    @Override
    public String chatWeather(String userMessage) {
        log.info("AI 天气查询：用户消息 = {}", userMessage);

        // 第一步：让大模型从用户消息中提取城市名
        String city = deepSeekClient.chat(EXTRACT_CITY_PROMPT, "从这句话中提取城市名：" + userMessage);
        log.info("大模型提取的城市名: {}", city);

        if (city == null || city.isBlank() || city.equals("无")) {
            // 没提到城市，让大模型直接友好回复
            String noCityReply = deepSeekClient.chat(
                    "你是一个天气助手。用户问了天气相关的问题但没有提到具体城市，" +
                    "请友好地提醒用户告诉你要查哪个城市的天气。回复简短自然。",
                    userMessage
            );
            return noCityReply != null ? noCityReply : "你想查哪个城市的天气呀？告诉我城市名~";
        }

        // 第二步：用提取的城市名查天气
        try {
            WeatherResponse weather = weatherService.getWeatherByCity(city.trim());

            // 第三步：把天气数据交给大模型拟人化回复
            String weatherInfo = String.format(
                    "城市：%s，天气：%s，温度：%s°C，湿度：%s%%，风向：%s，风力：%s级",
                    weather.city(), weather.weather(), weather.temperature(),
                    weather.humidity(), weather.windDirection(), weather.windPower()
            );
            String aiReply = deepSeekClient.chat(WEATHER_PROMPT,
                    "用户说：" + userMessage + "\n天气数据：" + weatherInfo);

            if (aiReply != null && !aiReply.isBlank()) {
                return aiReply;
            }

            // 大模型返回为空，降级
            return "【" + weather.city() + "天气】" + weather.weather()
                    + "，" + weather.temperature() + "°C";

        } catch (BusinessException e) {
            log.warn("天气查询失败: {}", e.getMessage());
            // 天气查不到，让大模型友好回复
            String failReply = deepSeekClient.chat(
                    "你是一个天气助手。用户查询的天气没有找到，请友好地告诉用户查询失败，建议检查城市名。",
                    "查询城市：" + city + "，错误：" + e.getMessage()
            );
            return failReply != null ? failReply : "抱歉，没有查到「" + city + "」的天气信息，换个名字试试？";
        }
    }
}