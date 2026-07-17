package ykd.ykd.ai.service;

public interface AiWeatherService {

    /**
     * 接收用户任意消息，智能判断是否要查天气，并用大模型拟人化回复。
     *
     * @param userMessage 用户的原始消息（可以是城市名，也可以是自然语言）
     * @return 大模型的拟人化回答
     */
    String chatWeather(String userMessage);
}