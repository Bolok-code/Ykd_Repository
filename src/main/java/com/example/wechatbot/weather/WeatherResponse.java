package com.example.wechatbot.weather;

import com.fasterxml.jackson.annotation.JsonProperty;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class WeatherResponse {

    private String status;
    private String count;
    private String info;
    private String infocode;

    @JsonProperty("lives")
    private LiveWeather[] lives;

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getCount() { return count; }
    public void setCount(String count) { this.count = count; }
    public String getInfo() { return info; }
    public void setInfo(String info) { this.info = info; }
    public String getInfocode() { return infocode; }
    public void setInfocode(String infocode) { this.infocode = infocode; }

    public LiveWeather[] getLives() { return lives; }
    public void setLives(LiveWeather[] lives) { this.lives = lives; }

    public boolean isSuccess() {
        return "1".equals(status);
    }

    public LiveWeather getFirstLive() {
        if (lives != null && lives.length > 0) {
            return lives[0];
        }
        return null;
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public static class LiveWeather {
        private String province;
        private String city;
        private String adcode;
        private String weather;
        private String temperature;
        private String winddirection;
        private String windpower;
        private String humidity;
        private String reporttime;

        public String getProvince() { return province; }
        public void setProvince(String province) { this.province = province; }
        public String getCity() { return city; }
        public void setCity(String city) { this.city = city; }
        public String getAdcode() { return adcode; }
        public void setAdcode(String adcode) { this.adcode = adcode; }
        public String getWeather() { return weather; }
        public void setWeather(String weather) { this.weather = weather; }
        public String getTemperature() { return temperature; }
        public void setTemperature(String temperature) { this.temperature = temperature; }
        public String getWinddirection() { return winddirection; }
        public void setWinddirection(String winddirection) { this.winddirection = winddirection; }
        public String getWindpower() { return windpower; }
        public void setWindpower(String windpower) { this.windpower = windpower; }
        public String getHumidity() { return humidity; }
        public void setHumidity(String humidity) { this.humidity = humidity; }
        public String getReporttime() { return reporttime; }
        public void setReporttime(String reporttime) { this.reporttime = reporttime; }

        public String toFriendlyString() {
            StringBuilder sb = new StringBuilder();
            sb.append("=== ").append(city).append("天气 ===\n");
            sb.append("---------------------\n");
            sb.append("位置: ").append(province).append(" ").append(city).append("\n");
            sb.append("温度: ").append(temperature).append("°C\n");
            sb.append("天气: ").append(weather).append("\n");
            sb.append("湿度: ").append(humidity).append("%\n");
            sb.append("风向: ").append(winddirection).append(" ").append(windpower).append("级\n");
            sb.append("更新: ").append(reporttime).append("\n");
            sb.append("---------------------");
            return sb.toString();
        }
    }
}
