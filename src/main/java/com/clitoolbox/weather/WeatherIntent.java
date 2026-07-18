package com.clitoolbox.weather;

import java.time.LocalDate;

/**
 * 从用户自然语言中解析出的天气查询条件。
 */
public record WeatherIntent(String city, LocalDate targetDate) {

    public WeatherIntent {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("城市名不能为空");
        }
        if (targetDate == null) {
            throw new IllegalArgumentException("目标日期不能为空");
        }
        city = city.trim();
    }
}
