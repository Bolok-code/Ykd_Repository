package com.clitoolbox.weather;

import java.time.LocalDate;

/**
 * 指定日期的天气预报结果。
 */
public record WeatherForecastResult(
        String city,
        LocalDate forecastDate,
        String dayDescription,
        String nightDescription,
        double highTemp,
        double lowTemp,
        int humidity,
        double windSpeed,
        long updateTime) {
}
