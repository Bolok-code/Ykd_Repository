package ykd.ykd.weather.api.dto;
import java.util.List;
public record WeatherResponse(
        String type,
        String province,
        String city,
        String weather,
        String temperature,
        String humidity,
        String windDirection,
        String windPower,
        String reportTime,
        List<ForecastDay> forecasts) {
}
