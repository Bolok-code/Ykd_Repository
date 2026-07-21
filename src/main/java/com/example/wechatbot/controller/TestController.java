package com.example.wechatbot.controller;

import com.example.wechatbot.handler.MessageHandler;
import com.example.wechatbot.weather.AmapWeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class TestController {

    @Autowired
    private AmapWeatherService weatherService;

    @Autowired
    private MessageHandler messageHandler;

    @GetMapping("/weather")
    public String getWeather(@RequestParam(defaultValue = "北京") String city) {
        return weatherService.queryWeather(city);
    }

    @GetMapping("/test")
    public String testMessage(@RequestParam(defaultValue = "help") String msg) {
        return messageHandler.processMessage(msg);
    }

    @GetMapping("/health")
    public String health() {
        return "服务运行正常";
    }
}
