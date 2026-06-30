package br.com.soldate.cron;

public final class AppDefaults {
    private AppDefaults() {}

    public static final String DB_URL = "jdbc:postgresql://localhost:5432/piloto";
    public static final String DB_USER = "dona_cron";
    public static final String DB_PASS = "";
    public static final int DB_POOL_MAX = 10;
    public static final int DB_POOL_MIN_IDLE = 2;
    public static final long DB_POOL_CONN_TIMEOUT_MS = 3000L;
    public static final long DB_POOL_IDLE_TIMEOUT_MS = 600000L;
    public static final long DB_POOL_MAX_LIFETIME_MS = 1800000L;

    public static final int PORT = 8086;
    public static final int WORKER_INTERVAL_SECONDS = 60;
    public static final int WORKER_BATCH_SIZE = 20;
    public static final int RESPONSE_BODY_MAX_CHARS = 20000;
}
