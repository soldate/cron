package br.com.soldate.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Enumeration;
import java.util.Locale;

final class JavaErrorLog {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JavaErrorLog() {}

    static void save(HttpServletRequest req, Throwable error) {
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement("""
            INSERT INTO util.erro_java (
                sistema, endpoint, metodo, path, query_string, remote_addr,
                request_id, exception_class, mensagem, stack_trace, headers, contexto
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, '{}'::jsonb)
            """)) {
            stmt.setString(1, "cron");
            stmt.setString(2, req == null ? null : req.getRequestURI());
            stmt.setString(3, req == null ? null : req.getMethod());
            stmt.setString(4, req == null ? null : req.getPathInfo());
            stmt.setString(5, req == null ? null : req.getQueryString());
            stmt.setString(6, req == null ? null : req.getRemoteAddr());
            stmt.setString(7, req == null ? null : header(req, "X-Request-Id"));
            stmt.setString(8, error == null ? null : error.getClass().getName());
            stmt.setString(9, error == null ? null : error.getMessage());
            stmt.setString(10, stackTrace(error));
            stmt.setString(11, headers(req).toString());
            stmt.executeUpdate();
        } catch (Exception ignored) {
            // Error logging must never hide the original failure.
        }
    }

    private static ObjectNode headers(HttpServletRequest req) {
        ObjectNode out = MAPPER.createObjectNode();
        if (req == null) return out;
        Enumeration<String> names = req.getHeaderNames();
        while (names != null && names.hasMoreElements()) {
            String name = names.nextElement();
            if (isSensitiveHeader(name)) {
                out.put(name, "[masked]");
                continue;
            }
            out.put(name, req.getHeader(name));
        }
        return out;
    }

    private static boolean isSensitiveHeader(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("authorization")
            || lower.equals("cookie")
            || lower.equals("set-cookie")
            || lower.equals("proxy-authorization")
            || lower.equals("x-api-key");
    }

    private static String header(HttpServletRequest req, String name) {
        String value = req.getHeader(name);
        return value == null || value.isBlank() ? null : value;
    }

    private static String stackTrace(Throwable error) {
        if (error == null) return null;
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
