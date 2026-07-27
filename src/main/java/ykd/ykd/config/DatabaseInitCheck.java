package ykd.ykd.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

@Component
public class DatabaseInitCheck {

    private static final Logger log = LoggerFactory.getLogger(DatabaseInitCheck.class);

    private final DataSource dataSource;

    public DatabaseInitCheck(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @PostConstruct
    void init() {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            log.info("SQLite 数据库连接成功: {}", conn.getMetaData().getURL());
        } catch (Exception e) {
            log.warn("数据库连接失败（可能在首次查询时重试）: {}", e.getMessage());
        }
    }
}
