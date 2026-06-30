package br.com.soldate.cron;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;

import java.sql.Connection;
import java.sql.SQLException;

public final class Db {
    private static final HikariDataSource DATA_SOURCE = buildDataSource();

    private Db() {}

    public static Connection getConnection() throws SQLException {
        return DATA_SOURCE.getConnection();
    }

    public static HikariPoolMXBean getPoolMxBean() {
        return DATA_SOURCE.getHikariPoolMXBean();
    }

    private static HikariDataSource buildDataSource() {
        String url = AppConfig.read("DB_URL", AppDefaults.DB_URL);
        String user = AppConfig.read("DB_USER", AppDefaults.DB_USER);
        String pass = AppConfig.read("DB_PASS", AppConfig.read("DB_PASSWORD", AppDefaults.DB_PASS));
        int maxPoolSize = readInt("DB_POOL_MAX", AppDefaults.DB_POOL_MAX);
        int minIdle = readInt("DB_POOL_MIN_IDLE", AppDefaults.DB_POOL_MIN_IDLE);
        long connectionTimeout = readLong("DB_POOL_CONN_TIMEOUT_MS", AppDefaults.DB_POOL_CONN_TIMEOUT_MS);
        long idleTimeout = readLong("DB_POOL_IDLE_TIMEOUT_MS", AppDefaults.DB_POOL_IDLE_TIMEOUT_MS);
        long maxLifetime = readLong("DB_POOL_MAX_LIFETIME_MS", AppDefaults.DB_POOL_MAX_LIFETIME_MS);

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(pass);
        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setConnectionTimeout(connectionTimeout);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        config.setPoolName("cron-pool");
        config.setAutoCommit(true);
        config.setConnectionTestQuery("SELECT 1");
        return new HikariDataSource(config);
    }

    private static int readInt(String key, int fallback) {
        try {
            return Integer.parseInt(AppConfig.read(key, String.valueOf(fallback)));
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static long readLong(String key, long fallback) {
        try {
            return Long.parseLong(AppConfig.read(key, String.valueOf(fallback)));
        } catch (Exception ex) {
            return fallback;
        }
    }
}
