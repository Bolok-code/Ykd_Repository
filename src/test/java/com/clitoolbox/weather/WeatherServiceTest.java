package com.clitoolbox.weather;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clitoolbox.exception.CliException;
import com.clitoolbox.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {
    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> response;

    private WeatherService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-18T04:00:00Z"),
                WeatherService.WEATHER_ZONE);
        service = new WeatherService(
                new ObjectMapper(),
                "weather-test-key",
                httpClient,
                clock);
    }

    @Test
    void queriesEnoughDailyEntriesAndSelectsTargetDate() throws Exception {
        when(response.body()).thenReturn("""
                {
                  "results": [{
                    "location": {"name": "杭州"},
                    "daily": [
                      {
                        "date": "2026-07-18",
                        "text_day": "多云",
                        "text_night": "多云",
                        "high": "35",
                        "low": "27",
                        "humidity": "60",
                        "wind_speed": "8.0"
                      },
                      {
                        "date": "2026-07-19",
                        "text_day": "晴",
                        "text_night": "多云",
                        "high": "36",
                        "low": "28",
                        "humidity": "62",
                        "wind_speed": "7.5"
                      }
                    ],
                    "last_update": "2026-07-18T12:00:00+08:00"
                  }]
                }
                """);
        when(httpClient.send(
                any(HttpRequest.class),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
                .thenReturn(response);

        WeatherForecastResult result =
                service.forecast("杭州", LocalDate.of(2026, 7, 19));

        assertEquals("杭州", result.city());
        assertEquals(LocalDate.of(2026, 7, 19), result.forecastDate());
        assertEquals("晴", result.dayDescription());
        assertEquals("多云", result.nightDescription());
        assertEquals(36, result.highTemp());
        assertEquals(28, result.lowTemp());
        assertEquals(62, result.humidity());
        assertEquals(7.5, result.windSpeed());

        ArgumentCaptor<HttpRequest> requestCaptor =
                ArgumentCaptor.forClass(HttpRequest.class);
        org.mockito.Mockito.verify(httpClient).send(
                requestCaptor.capture(),
                org.mockito.ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
        assertEquals(
                "start=0&days=2",
                requestCaptor.getValue().uri().getQuery()
                        .substring(requestCaptor.getValue().uri().getQuery().lastIndexOf("start")));
    }

    @Test
    void rejectsPastAndOutOfRangeDatesBeforeCallingApi() {
        CliException past = assertThrows(
                CliException.class,
                () -> service.forecast("杭州", LocalDate.of(2026, 7, 17)));
        assertEquals(ErrorCode.INVALID_INPUT, past.getErrorCode());

        CliException tooFar = assertThrows(
                CliException.class,
                () -> service.forecast("杭州", LocalDate.of(2026, 8, 2)));
        assertEquals(ErrorCode.INVALID_INPUT, tooFar.getErrorCode());
        verifyNoInteractions(httpClient);
    }
}
