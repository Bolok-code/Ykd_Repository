package com.clitoolbox.commands;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.clitoolbox.weather.WeatherResult;
import com.clitoolbox.weather.WeatherService;
import java.text.SimpleDateFormat;
import java.util.Date;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
@Lazy
public class WeatherCommand implements Command {
    private final WeatherService weatherService;

    public WeatherCommand(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    @Override
    public void run(String[] args) {
        String city = parseCityArg(args);
        WeatherResult result = weatherService.query(city);
        printResult(result);
    }

    private String parseCityArg(String[] args) {
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--city") || args[i].equals("-c")) {
                if (i + 1 < args.length && !args[i + 1].startsWith("-")) return args[i + 1];
            }
        }
        throw new CliException(ErrorCode.INVALID_INPUT,
                "请指定城市名。\n  用法: java -jar cli-toolbox.jar weather --city <城市名>\n  示例: java -jar cli-toolbox.jar weather --city 北京");
    }

    private void printResult(WeatherResult r) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        System.out.println("========== 实时天气 ==========");
        System.out.println("城市:     " + r.city());
        System.out.println("天气:     " + r.weatherDescription());
        System.out.printf("温度:     %.1f\u00B0C (体感 %.1f\u00B0C)%n", r.temperature(), r.feelsLike());
        System.out.printf("今日:     %.0f\u00B0C ~ %.0f\u00B0C%n", r.lowTemp(), r.highTemp());
        System.out.println("湿度:     " + r.humidity() + "%");
        System.out.printf("风速:     %.1f m/s%n", r.windSpeed());
        System.out.println("更新时间: " + sdf.format(new Date(r.updateTime())));
        System.out.println("==============================");
    }
}
