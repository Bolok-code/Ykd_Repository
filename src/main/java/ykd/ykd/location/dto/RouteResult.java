package ykd.ykd.location.dto;

import java.util.List;

/**
 * 高德路线规划结果。
 */
public record RouteResult(
        String mode,
        int distanceMeters,
        int durationSeconds,
        List<String> steps
) {
}
