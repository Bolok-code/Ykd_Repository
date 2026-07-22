package ykd.ykd.weather.api.dto;

import lombok.Builder;

@Builder
public record ForecastDay(
        String date,
        String week,
        String dayWeather,
        String nightWeather,
        String dayTemp,
        String nightTemp,
        String dayWind,
        String nightWind,
        String dayPower,
        String nightPower
) {
}
