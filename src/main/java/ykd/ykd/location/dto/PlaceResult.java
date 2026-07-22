package ykd.ykd.location.dto;

/**
 * 高德周边地点搜索结果。
 */
public record PlaceResult(
        String name,
        String address,
        String type,
        int distanceMeters,
        double longitude,
        double latitude
) {
}
