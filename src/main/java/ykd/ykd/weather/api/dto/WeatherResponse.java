package ykd.ykd.weather.api.dto;
import lombok.Builder;
import java.util.List;


@Builder
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
