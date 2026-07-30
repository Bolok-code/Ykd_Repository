package ykd.ykd.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SchemaMigration {

    private final JdbcTemplate jdbcTemplate;

    public SchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0)
    public void migrate() {
        try {
            jdbcTemplate.execute("ALTER TABLE reminder_task ADD COLUMN cron_expression TEXT");
            log.info("[SchemaMigration] 已添加 cron_expression 列");
        } catch (Exception e) {
            log.debug("[SchemaMigration] cron_expression 列已存在，跳过: {}", e.getMessage());
        }
    }
}
