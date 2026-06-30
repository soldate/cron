package br.com.soldate.cron;

public final class AppConfig {
    private AppConfig() {}

    public static String read(String key, String fallback) {
        String fromProp = System.getProperty(key);
        if (fromProp != null && !fromProp.isBlank()) return fromProp;
        String fromEnv = System.getenv(key);
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        return fallback;
    }
}
