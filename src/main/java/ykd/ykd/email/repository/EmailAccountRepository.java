package ykd.ykd.email.repository;

import ykd.ykd.email.model.EmailAccount;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface EmailAccountRepository {

    void save(String userId, EmailAccount account);

    EmailAccount findByUserId(String userId);

    boolean existsByUserId(String userId);

    void deleteByUserId(String userId);
}
