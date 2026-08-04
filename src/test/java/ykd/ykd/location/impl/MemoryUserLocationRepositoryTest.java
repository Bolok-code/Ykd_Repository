package ykd.ykd.location.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ykd.ykd.location.model.UserLocation;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class MemoryUserLocationRepositoryTest {

    private MemoryUserLocationRepository repository;

    @BeforeEach
    void setUp() {
        repository = new MemoryUserLocationRepository();
    }

    @Test
    void shouldSaveAndFindUserLocation() {
        UserLocation location = new UserLocation(
                "user-001",
                "杭州西湖区",
                "杭州市",
                120.1302,
                30.2596,
                LocalDateTime.now()
        );

        repository.save(location);

        Optional<UserLocation> result =
                repository.findByUserId("user-001");

        assertTrue(result.isPresent());
        assertEquals(location, result.get());
    }

    @Test
    void shouldUpdateLocationForSameUser() {
        UserLocation oldLocation = new UserLocation(
                "user-001",
                "杭州西湖区",
                "杭州市",
                120.1302,
                30.2596,
                LocalDateTime.now()
        );

        UserLocation newLocation = new UserLocation(
                "user-001",
                "杭州东站",
                "杭州市",
                120.2120,
                30.2907,
                LocalDateTime.now()
        );

        repository.save(oldLocation);
        repository.save(newLocation);

        UserLocation result = repository
                .findByUserId("user-001")
                .orElseThrow();

        assertEquals("杭州东站", result.address());
        assertEquals(newLocation, result);
    }

    @Test
    void shouldKeepDifferentUsersSeparated() {
        UserLocation userOneLocation = new UserLocation(
                "user-001",
                "杭州西湖区",
                "杭州市",
                120.1302,
                30.2596,
                LocalDateTime.now()
        );

        UserLocation userTwoLocation = new UserLocation(
                "user-002",
                "徐州云龙区",
                "徐州市",
                117.2841,
                34.2058,
                LocalDateTime.now()
        );

        repository.save(userOneLocation);
        repository.save(userTwoLocation);

        assertEquals(
                "杭州市",
                repository.findByUserId("user-001")
                        .orElseThrow()
                        .city()
        );

        assertEquals(
                "徐州市",
                repository.findByUserId("user-002")
                        .orElseThrow()
                        .city()
        );
    }

    @Test
    void shouldEvictLeastRecentlyUsedWhenOverCapacity() {
        // 容量上限 1000，插入 1001 个用户 → 最早插入且未再访问的 "u-0" 被逐出
        repository.save(loc("u-0"));
        for (int i = 1; i <= 1000; i++) {
            repository.save(loc("u-" + i));
        }

        assertFalse(repository.findByUserId("u-0").isPresent());
        assertTrue(repository.findByUserId("u-1000").isPresent());
    }

    private static UserLocation loc(String userId) {
        return new UserLocation(userId, "杭州西湖区", "杭州市", 120.1302, 30.2596, LocalDateTime.now());
    }
}