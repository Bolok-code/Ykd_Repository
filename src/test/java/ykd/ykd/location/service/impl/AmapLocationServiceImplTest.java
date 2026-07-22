package ykd.ykd.location.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;
import ykd.ykd.exception.BusinessException;
import ykd.ykd.exception.ErrorCode;
import ykd.ykd.location.dto.GeocodeResult;
import ykd.ykd.location.dto.PlaceResult;
import ykd.ykd.location.dto.RouteResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AmapLocationServiceImplTest {

    private RestTemplate restTemplate;
    private AmapLocationServiceImpl locationService;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        locationService = new AmapLocationServiceImpl(
                restTemplate,
                new ObjectMapper()
        );
        ReflectionTestUtils.setField(locationService, "apiKey", "test-key");
    }

    @Test
    void shouldParseGeocodeResponse() {
        String responseJson = """
                {
                  "status": "1",
                  "info": "OK",
                  "geocodes": [
                    {
                      "formatted_address": "浙江省杭州市西湖区",
                      "province": "浙江省",
                      "city": "杭州市",
                      "district": "西湖区",
                      "location": "120.130203,30.259324"
                    }
                  ]
                }
                """;
        when(restTemplate.getForObject(
                anyString(),
                eq(String.class),
                eq("test-key"),
                eq("杭州西湖区")
        )).thenReturn(responseJson);

        GeocodeResult result = locationService.geocode("杭州西湖区");

        assertEquals("杭州市", result.city());
        assertEquals(120.130203, result.longitude(), 0.000001);
        assertEquals(30.259324, result.latitude(), 0.000001);
    }

    @Test
    void shouldParseNearbyPlaces() {
        String responseJson = """
                {
                  "status": "1",
                  "info": "OK",
                  "pois": [
                    {
                      "name": "测试咖啡店",
                      "address": "西湖区测试路1号",
                      "type": "餐饮服务;咖啡厅",
                      "distance": "520",
                      "location": "120.131000,30.260000"
                    }
                  ]
                }
                """;
        when(restTemplate.getForObject(
                anyString(),
                eq(String.class),
                eq("test-key"),
                eq("120.13,30.25"),
                eq("咖啡店"),
                eq(3000)
        )).thenReturn(responseJson);

        List<PlaceResult> results = locationService.searchNearby(
                120.13,
                30.25,
                "咖啡店",
                3000
        );

        assertEquals(1, results.size());
        assertEquals("测试咖啡店", results.get(0).name());
        assertEquals(520, results.get(0).distanceMeters());
    }

    @Test
    void shouldParseWalkingRoute() {
        String responseJson = """
                {
                  "status": "1",
                  "info": "OK",
                  "route": {
                    "paths": [
                      {
                        "distance": "2500",
                        "duration": "1800",
                        "steps": [
                          {"instruction": "向东步行100米"},
                          {"instruction": "右转进入测试路"}
                        ]
                      }
                    ]
                  }
                }
                """;
        when(restTemplate.getForObject(
                anyString(),
                eq(String.class),
                eq("test-key"),
                eq("120.13,30.25"),
                eq("120.2,30.3")
        )).thenReturn(responseJson);

        RouteResult result = locationService.planRoute(
                120.13,
                30.25,
                120.2,
                30.3,
                "walking"
        );

        assertEquals("walking", result.mode());
        assertEquals(2500, result.distanceMeters());
        assertEquals(1800, result.durationSeconds());
        assertEquals(2, result.steps().size());
    }

    @Test
    void shouldRejectBlankAddressWithoutCallingAmap() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> locationService.geocode(" ")
        );

        assertEquals(ErrorCode.LOCATION_NOT_FOUND, exception.getErrorCode());
        verifyNoInteractions(restTemplate);
    }

    @Test
    void shouldRejectUnknownAddress() {
        String responseJson = """
                {
                  "status": "1",
                  "info": "OK",
                  "geocodes": []
                }
                """;
        when(restTemplate.getForObject(
                anyString(),
                eq(String.class),
                eq("test-key"),
                eq("不存在的地址")
        )).thenReturn(responseJson);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> locationService.geocode("不存在的地址")
        );

        assertEquals(ErrorCode.LOCATION_NOT_FOUND, exception.getErrorCode());
        assertTrue(exception.getMessage().contains("不存在的地址"));
    }
}
