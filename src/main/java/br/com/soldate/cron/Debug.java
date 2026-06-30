package br.com.soldate.cron;

import jakarta.servlet.http.HttpServletRequest;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

public final class Debug {
    private static final int LEVEL = readLevel();
    private static final ThreadLocal<Integer> REQUEST_LEVEL = new ThreadLocal<>();
    private static final Set<String> SENSITIVE_KEYS = Set.of("senha", "password", "token", "access_token", "refresh_token");

    private Debug() {}

    public static int level() {
        return LEVEL;
    }

    public static boolean enabled(int level) {
        return effectiveLevel() >= level;
    }

    public static void log(int level, String tag, String message) {
        if (!enabled(level)) return;
        System.out.println("[" + Instant.now() + "] [debug:" + effectiveLevel() + "] [" + tag + "] " + message);
    }

    public static void request(int level, HttpServletRequest req, String note) {
        if (!enabled(level)) return;
        StringBuilder sb = new StringBuilder();
        sb.append(req.getMethod()).append(" ").append(req.getRequestURI());
        String query = req.getQueryString();
        if (query != null && !query.isBlank()) sb.append("?").append(sanitizeQueryString(query));
        sb.append(" from ").append(req.getRemoteAddr());
        if (note != null && !note.isBlank()) sb.append(" ").append(note);
        log(level, "http", sb.toString());
    }

    public static void response(int level, String tag, int status, int bytes) {
        if (!enabled(level)) return;
        log(level, tag, "response status=" + status + " bytes=" + bytes);
    }

    public static void beginRequest(HttpServletRequest req) {
        if (req == null) return;
        Integer level = parseLevel(req.getParameter("debug"));
        if (level != null) REQUEST_LEVEL.set(level);
    }

    public static void endRequest() {
        REQUEST_LEVEL.remove();
    }

    public static String truncate(String value, int max) {
        if (value == null) return null;
        if (value.length() <= max) return value;
        return value.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static String sanitizeQueryString(String query) {
        String[] parts = query.split("&");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append("&");
            String part = parts[i];
            int idx = part.indexOf('=');
            String key = idx >= 0 ? part.substring(0, idx) : part;
            String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
            if (SENSITIVE_KEYS.contains(decodedKey.toLowerCase(Locale.ROOT)) || decodedKey.toLowerCase(Locale.ROOT).contains("token")) {
                sb.append(key).append("=***");
            } else {
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static int effectiveLevel() {
        Integer requestLevel = REQUEST_LEVEL.get();
        return requestLevel != null ? requestLevel : LEVEL;
    }

    private static Integer parseLevel(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            int parsed = Integer.parseInt(raw.trim());
            if (parsed < 0) return 0;
            return Math.min(2, parsed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static int readLevel() {
        Integer parsed = parseLevel(AppConfig.read("DEBUG", AppConfig.read("DEBUG_LEVEL", "0")));
        return parsed == null ? 0 : parsed;
    }
}
