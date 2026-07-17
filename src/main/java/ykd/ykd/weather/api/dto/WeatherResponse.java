package ykd.ykd.weather.api.dto;

import lombok.Builder;

/**
 * 心知天气 API 响应模型（record + @Builder）。
 */
@Builder
public record WeatherResponse(
        String type,
        String city,
        String weather,
        String temperature,
        String humidity,
        String windDirection,
        String windPower,
        String reportTime
) {
}