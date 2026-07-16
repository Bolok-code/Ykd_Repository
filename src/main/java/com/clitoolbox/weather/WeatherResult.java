package com.clitoolbox.weather;

public record WeatherResult(
        String city,
        String weatherDescription,
        double temperature,
        double feelsLike,
        int humidity,
        double windSpeed,
        long updateTime,
        double highTemp,
        double lowTemp
) {
    public WeatherResult(String city, String weatherDescription, double temperature,
                        double feelsLike, int humidity, double windSpeed, long updateTime) {
        this(city, weatherDescription, temperature, feelsLike, humidity, windSpeed, updateTime, temperature, temperature);
    }
}
