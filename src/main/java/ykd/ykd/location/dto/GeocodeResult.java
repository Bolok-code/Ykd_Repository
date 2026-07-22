package ykd.ykd.location.dto;

/**
 * 高德地理编码结果。
 */
public record GeocodeResult(
        String formattedAddress,
        String province,
        String city,
        String district,
        double longitude,
        double latitude
) {
}
