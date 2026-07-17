package ykd.ykd.weather.api;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ykd.ykd.weather.api.dto.WeatherResponse;
import ykd.ykd.weather.service.WeatherService;

@RestController
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    @GetMapping("/weather/search")
    public WeatherResponse searchWeather(@RequestParam String city) {
        return weatherService.getWeatherByCity(city);
    }
}