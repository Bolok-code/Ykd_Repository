package ykd.ykd.llm.tools;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.weather.service.WeatherService;

@Slf4j
@Component
@RequiredArgsConstructor
public class WeatherTools {
    private final WeatherService weatherService;

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
            return ErrorCode.AI_WEATHER_FAILED.getDefaultMessage();
        }
    }
}
