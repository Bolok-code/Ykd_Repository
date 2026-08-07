package ykd.ykd.weather.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    @Value("${http.client.connect-timeout-ms:3000}")
    private int connectTimeoutMs;

    @Value("${http.client.read-timeout-ms:10000}")
    private int readTimeoutMs;

    @Bean
    public RestTemplate restTemplate() {
        // 必须显式配置超时：默认 connect/read timeout 为无限，
        // 外部 API（如高德）挂起时会无限阻塞调度线程，拖垮所有用户消息处理。
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return new RestTemplate(factory);
    }
}
