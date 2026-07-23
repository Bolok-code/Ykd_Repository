package ykd.ykd.email.repository;

import org.springframework.stereotype.Repository;
import ykd.ykd.email.model.EmailAccount;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
public class MemoryEmailAccountRepository implements EmailAccountRepository {

    private final Map<String, EmailAccount> store = new ConcurrentHashMap<>();

    @Override
    public void save(String userId, EmailAccount account) {
        store.put(userId, account);
    }

    @Override
    public EmailAccount findByUserId(String userId) {
        return store.get(userId);
    }

    @Override
    public boolean existsByUserId(String userId) {
        return store.containsKey(userId);
    }

    @Override
    public void deleteByUserId(String userId) {
        store.remove(userId);
    }
}