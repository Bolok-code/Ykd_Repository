package ykd.ykd.weather;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ykd.ykd.exception.BusinessException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 天气查询服务测试 —— 覆盖正常查询、多城市、边界情况。
 *
 * <p>不依赖 Spring 上下文，手动构造 WeatherService 对象。</p>
 */
class WeatherServiceTest {

    private WeatherService service;

    @BeforeEach
    void setUp() {
        service = new WeatherService(
                "https://api.seniverse.com/v3/weather/now.json",
                "P7KOXeLWc93vXyd4m",
                "SGR2KR3Mz6Ro-NqTJ",
                1800
        );
    }

    // ==================== 正常场景 ====================

    @Test
    @DisplayName("查询北京天气 — 应返回非空结果")
    void testBeijing() {
        WeatherResponse wr = service.queryWeather("北京");
        assertNotNull(wr);
        assertNotNull(wr.results);
        assertFalse(wr.results.isEmpty());
        assertEquals("北京", wr.results.get(0).location.name);
        System.out.println("[测试] 北京：");
        System.out.println(wr.toDisplayString());
    }

    @Test
    @DisplayName("查询上海天气 — 应返回非空结果")
    void testShanghai() {
        WeatherResponse wr = service.queryWeather("上海");
        assertNotNull(wr);
        assertNotNull(wr.results);
        assertFalse(wr.results.isEmpty());
        assertEquals("上海", wr.results.get(0).location.name);
        System.out.println("[测试] 上海：");
        System.out.println(wr.toDisplayString());
    }

    @Test
    @DisplayName("查询广州天气 — 应返回非空结果")
    void testGuangzhou() {
        WeatherResponse wr = service.queryWeather("广州");
        assertNotNull(wr);
        assertNotNull(wr.results);
        assertFalse(wr.results.isEmpty());
        assertEquals("广州", wr.results.get(0).location.name);
        System.out.println("[测试] 广州：");
        System.out.println(wr.toDisplayString());
    }

    @Test
    @DisplayName("查询深圳天气 — 应返回非空结果")
    void testShenzhen() {
        WeatherResponse wr = service.queryWeather("深圳");
        assertNotNull(wr);
        assertNotNull(wr.results);
        assertFalse(wr.results.isEmpty());
        assertEquals("深圳", wr.results.get(0).location.name);
        System.out.println("[测试] 深圳：");
        System.out.println(wr.toDisplayString());
    }

    // ==================== 边界情况 ====================

    @Test
    @DisplayName("空城市名 → 抛 BusinessException")
    void testNullCity() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.queryWeather(null));
        assertEquals("E400", e.getErrorCode());
        assertTrue(e.getMessage().contains("城市名不能为空"));
    }

    @Test
    @DisplayName("空白城市名 → 抛 BusinessException")
    void testBlankCity() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.queryWeather("   "));
        assertEquals("E400", e.getErrorCode());
    }

    @Test
    @DisplayName("空字符串城市名 → 抛 BusinessException")
    void testEmptyCity() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.queryWeather(""));
        assertEquals("E400", e.getErrorCode());
    }

    @Test
    @DisplayName("无效城市名 → 抛 BusinessException")
    void testInvalidCity() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.queryWeather("不存在的城市XYZ"));
        assertTrue(e.getMessage().contains("不存在的城市XYZ"));
    }

    @Test
    @DisplayName("含特殊字符的城市名 → 抛 BusinessException")
    void testSpecialCharCity() {
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.queryWeather("北京<script>"));
        assertEquals("E400", e.getErrorCode());
        assertTrue(e.getMessage().contains("非法字符"));
    }
}
