package ykd.ykd.location.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.location.dto.GeocodeResult;
import ykd.ykd.location.model.UserLocation;
import ykd.ykd.location.repository.UserLocationRepository;
import ykd.ykd.location.service.AmapLocationService;
import ykd.ykd.weather.service.WeatherService;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserLocationServiceImplTest {

    private UserLocationRepository repository;
    private AmapLocationService amapLocationService;
    private WeatherService weatherService;
    private UserLocationServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(UserLocationRepository.class);
        amapLocationService = mock(AmapLocationService.class);
        weatherService = mock(WeatherService.class);
        service = new UserLocationServiceImpl(
                repository,
                amapLocationService,
                weatherService
        );
    }

    @Test
    void shouldGeocodeAndSaveCurrentLocation() {
        when(amapLocationService.geocode("杭州西湖区"))
                .thenReturn(new GeocodeResult(
                        "浙江省杭州市西湖区",
                        "浙江省",
                        "杭州市",
                        "西湖区",
                        120.130203,
                        30.259324
                ));

        UserLocation result = service.setCurrentLocation(
                "user-001",
                "杭州西湖区"
        );

        ArgumentCaptor<UserLocation> captor =
                ArgumentCaptor.forClass(UserLocation.class);
        verify(repository).save(captor.capture());
        assertEquals("user-001", captor.getValue().userId());
        assertEquals("杭州市", captor.getValue().city());
        assertEquals(120.130203, result.longitude(), 0.000001);
    }

    @Test
    void shouldUseSavedCityForWeather() {
        UserLocation location = new UserLocation(
                "user-001",
                "浙江省杭州市西湖区",
                "杭州市",
                120.130203,
                30.259324,
                LocalDateTime.now()
        );
        when(repository.findByUserId("user-001"))
                .thenReturn(Optional.of(location));
        when(weatherService.getWeatherText("杭州市", "base"))
                .thenReturn("晴 30°C");

        String result = service.getLocalWeather("user-001", "base");

        assertEquals("晴 30°C", result);
        verify(weatherService).getWeatherText("杭州市", "base");
    }

    @Test
    void shouldAskUserToSetLocationWhenMissing() {
        when(repository.findByUserId("user-001"))
                .thenReturn(Optional.empty());

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.getCurrentLocation("user-001")
        );

        assertEquals(
                ErrorCode.CURRENT_LOCATION_MISSING,
                exception.getErrorCode()
        );
    }
}
