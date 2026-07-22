package ykd.ykd.llm.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.weather.service.WeatherService;

@Component
public class WeatherTools {
    private static final Logger log = LoggerFactory.getLogger(WeatherTools.class);
        private final WeatherService weatherService;

    public WeatherTools(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(description = "查询指定城市的实时天气")
    public String getWeather(@ToolParam(description = "中文城市名，如 北京、上海") String city) {
        long start = System.currentTimeMillis();
        log.info("[WeatherTool] 被调用: city={}", city);
        try {
            String result = weatherService.getWeatherText(city);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[WeatherTool] 查询成功: elapsed={}ms, city={}, result={}", elapsed, city, result);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[WeatherTool] 查询失败: elapsed={}ms, city={}, error={}", elapsed, city, e.getMessage(), e);
            return "❌ " + ErrorCode.AI_WEATHER_FAILED.getDefaultMessage();
        }
    }
}
