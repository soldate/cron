package br.com.soldate.cron;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

public final class TaskExecutor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    private TaskExecutor() {}

    public static ExecutionResult runNow(UUID taskId) throws Exception {
        try (Connection conn = Db.getConnection()) {
            Task task = loadTask(conn, taskId);
            if (task == null) return new ExecutionResult(false, null, "tarefa nao encontrada");
            UUID executionId = execute(task);
            return new ExecutionResult(true, executionId.toString(), null);
        }
    }

    static UUID execute(Task task) throws Exception {
        UUID executionId = createExecution(task.id(), task.tentativasFeitas() + 1);
        long started = System.nanoTime();
        Integer status = null;
        String responseBody = null;
        String responseHeaders = null;
        String error = null;
        boolean success = false;

        try {
            HttpRequest request = buildRequest(task);
            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            responseBody = limit(response.body(), AppDefaults.RESPONSE_BODY_MAX_CHARS);
            responseHeaders = MAPPER.writeValueAsString(response.headers().map());
            success = status >= 200 && status < 300;
        } catch (Exception ex) {
            error = ex.getMessage();
        }

        int durationMs = (int) Math.min(Integer.MAX_VALUE, Duration.ofNanos(System.nanoTime() - started).toMillis());
        finishExecution(executionId, durationMs, success, status, error, responseBody, responseHeaders);
        updateTaskAfterExecution(task, success);
        return executionId;
    }

    static Task loadTask(Connection conn, UUID taskId) throws Exception {
        String sql = """
            SELECT id, nome, url, metodo, headers::text, payload::text, tipo_agendamento,
                   intervalo_minutos, timeout_segundos, max_tentativas, tentativas_feitas
            FROM cron.tarefa
            WHERE id = ? AND ativo = true
            """;
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, taskId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) return null;
                return taskFromResult(rs);
            }
        }
    }

    static Task taskFromResult(ResultSet rs) throws Exception {
        return new Task(
            (UUID) rs.getObject("id"),
            rs.getString("nome"),
            rs.getString("url"),
            rs.getString("metodo"),
            rs.getString("headers"),
            rs.getString("payload"),
            rs.getString("tipo_agendamento"),
            rs.getObject("intervalo_minutos") == null ? null : rs.getInt("intervalo_minutos"),
            rs.getInt("timeout_segundos"),
            rs.getInt("max_tentativas"),
            rs.getInt("tentativas_feitas")
        );
    }

    private static HttpRequest buildRequest(Task task) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(task.url()))
            .timeout(Duration.ofSeconds(task.timeoutSeconds()));

        ObjectNode headers = (ObjectNode) MAPPER.readTree(task.headersJson());
        headers.fields().forEachRemaining(entry -> builder.header(entry.getKey(), entry.getValue().asText()));

        String payload = task.payloadJson();
        switch (task.method()) {
            case "GET" -> builder.GET();
            case "DELETE" -> builder.method("DELETE", HttpRequest.BodyPublishers.ofString(payload));
            default -> {
                builder.header("Content-Type", "application/json");
                builder.method(task.method(), HttpRequest.BodyPublishers.ofString(payload));
            }
        }
        return builder.build();
    }

    private static UUID createExecution(UUID taskId, int attempt) throws Exception {
        String sql = "INSERT INTO cron.execucao (tarefa_id, tentativa) VALUES (?, ?) RETURNING id";
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setObject(1, taskId);
            stmt.setInt(2, attempt);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                return (UUID) rs.getObject("id");
            }
        }
    }

    private static void finishExecution(UUID executionId, int durationMs, boolean success, Integer status, String error, String body, String headers) throws Exception {
        String sql = """
            UPDATE cron.execucao
            SET finalizada_em = now(), duracao_ms = ?, sucesso = ?, status_http = ?,
                erro = ?, resposta_body = ?, resposta_headers = ?::jsonb
            WHERE id = ?
            """;
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, durationMs);
            stmt.setBoolean(2, success);
            if (status == null) stmt.setObject(3, null); else stmt.setInt(3, status);
            stmt.setString(4, error);
            stmt.setString(5, body);
            stmt.setString(6, headers == null ? null : headers);
            stmt.setObject(7, executionId);
            stmt.executeUpdate();
        }
    }

    private static void updateTaskAfterExecution(Task task, boolean success) throws Exception {
        LocalDateTime next = null;
        boolean active = true;
        int attempts = success ? 0 : task.tentativasFeitas() + 1;

        if ("unico".equals(task.scheduleType())) {
            active = !success && attempts < task.maxAttempts();
            next = active ? LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0) : null;
        } else if (success) {
            next = LocalDateTime.now().plusMinutes(task.intervalMinutes()).withSecond(0).withNano(0);
        } else if (attempts < task.maxAttempts()) {
            next = LocalDateTime.now().plusMinutes(1).withSecond(0).withNano(0);
        } else {
            next = LocalDateTime.now().plusMinutes(task.intervalMinutes()).withSecond(0).withNano(0);
            attempts = 0;
        }

        String sql = """
            UPDATE cron.tarefa
            SET ultima_execucao = now(), proxima_execucao = ?, ativo = ?, tentativas_feitas = ?, atualizado_em = now()
            WHERE id = ?
            """;
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (next == null) stmt.setObject(1, null); else stmt.setTimestamp(1, Timestamp.valueOf(next));
            stmt.setBoolean(2, active);
            stmt.setInt(3, attempts);
            stmt.setObject(4, task.id());
            stmt.executeUpdate();
        }
    }

    private static String limit(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) return value;
        return value.substring(0, maxChars);
    }

    public record Task(
        UUID id,
        String name,
        String url,
        String method,
        String headersJson,
        String payloadJson,
        String scheduleType,
        Integer intervalMinutes,
        int timeoutSeconds,
        int maxAttempts,
        int tentativasFeitas
    ) {}

    public record ExecutionResult(boolean ok, String executionId, String error) {}
}
