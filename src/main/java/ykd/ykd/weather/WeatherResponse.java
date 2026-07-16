package ykd.ykd.weather;

import com.google.gson.annotations.SerializedName;

import java.util.List;

/**
 * 心知天气 API 响应模型。
 *
 * <p>JSON 示例：
 * <pre>
 * { "results": [{ "location": { "name": "北京" }, "now": { "text": "晴", "temperature": "25" } }] }
 * </pre>
 */
public class WeatherResponse {

    public List<Result> results;

    public static class Result {
        public Location location;
        public Now now;
        @SerializedName("last_update")
        public String lastUpdate;
    }

    public static class Location {
        public String id;
        public String name;
        public String country;
        @SerializedName("timezone")
        public String timezone;
    }

    public static class Now {
        public String text;
        public String code;
        public String temperature;
        @SerializedName("feels_like")
        public String feelsLike;
        public String humidity;
        @SerializedName("wind_direction")
        public String windDirection;
        @SerializedName("wind_speed")
        public String windSpeed;
        @SerializedName("wind_scale")
        public String windScale;
        @SerializedName("dew_point")
        public String dewPoint;
    }

    /**
     * 从响应中提取格式化的天气信息字符串。
     */
    public String toDisplayString() {
        if (results == null || results.isEmpty()) {
            return "未查询到天气数据";
        }
        Result r = results.get(0);
        if (r.location == null || r.now == null) {
            return "天气数据不完整";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("城市: ").append(r.location.name).append("\n");
        sb.append("天气: ").append(r.now.text).append("\n");
        sb.append("温度: ").append(r.now.temperature).append(" ℃\n");

        // 以下字段仅付费版返回，无数据时不显示
        if (r.now.feelsLike != null && !r.now.feelsLike.isBlank()) {
            sb.append("体感: ").append(r.now.feelsLike).append(" ℃\n");
        }
        if (r.now.humidity != null && !r.now.humidity.isBlank()) {
            sb.append("湿度: ").append(r.now.humidity).append("%\n");
        }
        if (r.now.windDirection != null && !r.now.windDirection.isBlank()
                || r.now.windSpeed != null && !r.now.windSpeed.isBlank()) {
            sb.append("风向: ")
                    .append(r.now.windDirection != null ? r.now.windDirection : "-")
                    .append(" (风力 ")
                    .append(r.now.windScale != null ? r.now.windScale + " 级" : "-")
                    .append(")\n");
        }
        if (r.lastUpdate != null && !r.lastUpdate.isBlank()) {
            sb.append("更新时间: ").append(r.lastUpdate);
        }
        return sb.toString().stripTrailing();
    }
}
