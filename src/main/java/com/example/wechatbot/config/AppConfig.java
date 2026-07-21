package com.example.wechatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app")
public class AppConfig {

    private AmapConfig amap = new AmapConfig();

    public AmapConfig getAmap() { return amap; }
    public void setAmap(AmapConfig amap) { this.amap = amap; }

    public static class AmapConfig {
        private String apiKey = "";
        private String baseUrl = "https://restapi.amap.com/v3";

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    }
}