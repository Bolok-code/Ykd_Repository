package ykd.ykd.location.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.location.dto.GeocodeResult;
import ykd.ykd.location.dto.PlaceResult;
import ykd.ykd.location.dto.RouteResult;
import ykd.ykd.location.model.UserLocation;
import ykd.ykd.location.repository.UserLocationRepository;
import ykd.ykd.location.service.AmapLocationService;
import ykd.ykd.location.service.UserLocationService;
import ykd.ykd.weather.service.WeatherService;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
 public class UserLocationServiceImpl implements UserLocationService {

    private final UserLocationRepository userLocationRepository;
    private final AmapLocationService amapLocationService;
    private final WeatherService weatherService;

    @Override
    public UserLocation setCurrentLocation(String userId, String address) {
        requireUserId(userId);
        GeocodeResult geocode = amapLocationService.geocode(address);
        UserLocation location = new UserLocation(
                userId,
                geocode.formattedAddress(),
                geocode.city(),
                geocode.longitude(),
                geocode.latitude(),
                LocalDateTime.now()
        );
        userLocationRepository.save(location);
        return location;
    }

    @Override
    public UserLocation getCurrentLocation(String userId) {
        requireUserId(userId);
        return userLocationRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException(
                        ErrorCode.CURRENT_LOCATION_MISSING,
                        "请先告诉我您的位置，例如：我现在在杭州西湖区"
                ));
    }

    @Override
    public String getLocalWeather(String userId, String type) {
        UserLocation location = getCurrentLocation(userId);
        String normalizedType = "all".equalsIgnoreCase(type)
                ? "all"
                : "base";
        return weatherService.getWeatherText(
                location.city(),
                normalizedType
        );
    }

    @Override
    public List<PlaceResult> searchNearby(
            String userId,
            String keyword,
            int radiusMeters
    ) {
        UserLocation location = getCurrentLocation(userId);
        return amapLocationService.searchNearby(
                location.longitude(),
                location.latitude(),
                keyword,
                radiusMeters
        );
    }

    @Override
    public RouteResult planRoute(
            String userId,
            String destination,
            String mode
    ) {
        UserLocation origin = getCurrentLocation(userId);
        GeocodeResult destinationPoint = amapLocationService.geocode(destination);
        return amapLocationService.planRoute(
                origin.longitude(),
                origin.latitude(),
                destinationPoint.longitude(),
                destinationPoint.latitude(),
                mode
        );
    }

    private void requireUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(
                    ErrorCode.MESSAGE_PROCESS_FAILED,
                    "无法识别当前微信用户"
            );
        }
    }
}
