package ykd.ykd.exception;
import lombok.Getter;
@Getter
public enum ErrorCode {
    API_ERROR("API_ERROR", "高德API调用失败"),
    CITY_NOT_FOUND("CITY_NOT_FOUND", "未找到对应城市"),
    NETWORK_ERROR("NETWORK_ERROR", "网络请求失败"),
    ANALYSIS_ERROR("ANALYSIS_ERROR", "解析天气数据失败");
    private final String code;
    private final String defaultMessage;

    ErrorCode(String code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }
}