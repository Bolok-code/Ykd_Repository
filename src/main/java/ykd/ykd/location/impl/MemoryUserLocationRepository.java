package ykd.ykd.location.impl;

import org.springframework.stereotype.Repository;
import ykd.ykd.location.model.UserLocation;
import ykd.ykd.location.repository.UserLocationRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MemoryUserLocationRepository implements UserLocationRepository {

    private final Map<String, UserLocation> locations =
            new ConcurrentHashMap<>();

    @Override
    public void save(UserLocation location) {
        if (location == null) {
            throw new IllegalArgumentException("位置不能为空");
        }

        if (location.userId() == null
                || location.userId().isBlank()) {
            throw new IllegalArgumentException("用户ID不能为空");
        }

        locations.put(location.userId(), location);
    }

    @Override
    public Optional<UserLocation> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(locations.get(userId));
    }
}
