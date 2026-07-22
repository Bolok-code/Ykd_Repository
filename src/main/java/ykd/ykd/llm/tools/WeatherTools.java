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
    public String getWeather(
            @ToolParam(description = "中文城市名，如 北京、上海") String city,
            @ToolParam(description = "查询类型：base 查询实时天气，all 查询未来几天预报") String type) {
        long start = System.currentTimeMillis();
        log.info("[WeatherTool] 被调用: city={}, type={}", city,type);
        try {
            String result = weatherService.getWeatherText(city, type);
            long elapsed = System.currentTimeMillis() - start;
            log.info("[WeatherTool] 查询成功: elapsed={}ms, city={}, type={} result={}", elapsed, city, type, result);
            return result;
        } catch (Exception e) {
            long elapsed = System.currentTimeMillis() - start;
            log.error("[WeatherTool] 查询失败: elapsed={}ms, city={}, type={} error={}", elapsed, city, type, e.getMessage(), e);
            return "❌ " + ErrorCode.AI_WEATHER_FAILED.getDefaultMessage();
        }
    }
}
