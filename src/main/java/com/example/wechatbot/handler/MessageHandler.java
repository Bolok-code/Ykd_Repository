package com.example.wechatbot.handler;

import com.example.wechatbot.service.AiChatService;
import com.example.wechatbot.weather.AmapWeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class MessageHandler {

    private static final Logger log = LoggerFactory.getLogger(MessageHandler.class);

    @Autowired
    private AmapWeatherService amapWeatherService;

    @Autowired
    private AiChatService aiChatService;

    public String processImage(byte[] imageData) {
        return aiChatService.analyzeImage(imageData);
    }

    public String processMessage(String content) {
        return processMessage(content, null);
    }

    public String processMessage(String content, String userId) {
        if (content == null || content.isEmpty()) {
            return null;
        }

        content = content.trim();

        // Weather query - supports: "天气 北京", "查询杭州天气", "北京天气"
        if (content.contains("天气") || content.toLowerCase().contains("weather")) {
            String city = content;
            if (city.startsWith("天气")) {
                city = city.substring(2).trim();
            } else if (city.endsWith("天气")) {
                city = city.substring(0, city.length() - 2).trim();
                city = city.replaceAll("^(查询|查)", "").trim();
            } else if (city.toLowerCase().contains("weather")) {
                city = city.replaceAll("(?i)(weather|query|check|the)\\s*", "").trim();
            }
            if (city.isEmpty()) return "请输入城市名，例如：天气 北京";
            return amapWeatherService.queryWeather(city);
        }

        if (content.equalsIgnoreCase("ping")) {
            return "pong - service is running";
        }

        if (content.equalsIgnoreCase("help") || content.equals("help") || content.equals("?")) {
            return "Available commands: ping, help, weather 城市, or just chat";
        }

        // TTS command
        if (content.startsWith("朗读")) {
            String text = content.substring(2).trim();
            if (text.isEmpty()) return "请输入要朗读的内容，例如：朗读 你好";
            return aiChatService.synthesizeSpeech(text);
        }

        return aiChatService.chat(content, userId);
    }
}
