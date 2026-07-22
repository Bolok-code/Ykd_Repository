package ykd.ykd.weather.service;
import ykd.ykd.weather.api.dto.WeatherResponse;
public interface WeatherService {
    WeatherResponse getWeatherByCity(String city, String type);

    // WeatherService.java
    String getWeatherText(String city,String type);

}
