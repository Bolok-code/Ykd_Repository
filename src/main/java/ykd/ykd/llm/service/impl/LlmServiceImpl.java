package ykd.ykd.llm.service.impl;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.llm.service.LlmService;
import ykd.ykd.weather.service.WeatherService;

@Slf4j
@Service
public class LlmServiceImpl implements LlmService {

    private final ChatClient chatClient;
    private final WeatherService weatherService;

    public LlmServiceImpl(ChatClient.Builder builder, WeatherService weatherService) {
        this.chatClient = builder.build();
        this.weatherService = weatherService;
    }

    @Tool(description = "查询指定城市的实时天气")
    public String getWeather(@ToolParam(description = "中文城市名，如 北京、上海") String city) {
        log.info("AI调用天气工具: city={}", city);
        try {
            return weatherService.getWeatherText(city);
        } catch (Exception e) {
            log.error("天气工具异常: city={}", city, e);
            return ErrorCode.AI_WEATHER_FAILED.getDefaultMessage();
        }
    }



    @Override
    public String chat(String content) {
        log.info("AI请求: {}", content);
        return chatClient.prompt()
//                .system("你是微信聊天助手，你的人格是一个温柔知性的大姐姐")
                .user(content)
                .tools(this)
                .call()
                .content();
    }
}
