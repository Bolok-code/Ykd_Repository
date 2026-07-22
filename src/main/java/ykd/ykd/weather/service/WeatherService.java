package ykd.ykd.weather.service;
import org.springframework.stereotype.Service;
import ykd.ykd.weather.api.dto.WeatherResponse;


public interface WeatherService {

    WeatherResponse getWeatherByCity(String city, String type);

    // WeatherService.java
    String getWeatherText(String city);

}
