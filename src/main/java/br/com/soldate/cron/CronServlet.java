package br.com.soldate.cron;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class CronServlet extends HttpServlet {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Debug.beginRequest(req);
        try {
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();
            if (path.isBlank() || "/tasks".equals(path)) {
                listTasks(resp);
            } else if (path.startsWith("/tasks/") && path.endsWith("/executions")) {
                listExecutions(path, resp);
            } else if ("/executions".equals(path)) {
                listRecentExecutions(resp);
            } else {
                notFound(resp);
            }
        } catch (Exception ex) {
            fail(resp, ex);
        } finally {
            Debug.endRequest();
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Debug.beginRequest(req);
        try {
            String path = req.getPathInfo() == null ? "" : req.getPathInfo();
            if (path.isBlank() || "/tasks".equals(path)) {
                createTask(req, resp);
            } else if (path.startsWith("/tasks/") && path.endsWith("/run")) {
                runTask(path, resp);
            } else {
                notFound(resp);
            }
        } catch (Exception ex) {
            fail(resp, ex);
        } finally {
            Debug.endRequest();
        }
    }

    private static void createTask(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        JsonNode body = readBody(req);
        String nome = requiredText(body, "nome");
        String url = requiredText(body, "url");
        String metodo = text(body, "metodo", "POST").toUpperCase();
        String tipo = text(body, "tipo_agendamento", text(body, "tipoAgendamento", "intervalo"));
        int timeout = intValue(body, "timeout_segundos", intValue(body, "timeoutSegundos", 30));
        int maxTentativas = intValue(body, "max_tentativas", intValue(body, "maxTentativas", 1));
        int retryIntervalo = intValue(body, "retry_intervalo_minutos", intValue(body, "retryIntervaloMinutos", 1));
        JsonNode headers = objectValue(body, "headers");
        JsonNode payload = objectValue(body, "payload");
        Integer intervalo = nullableInt(body, "intervalo_minutos", nullableInt(body, "intervaloMinutos", null));
        LocalDateTime executarEm = parseDateTime(text(body, "executar_em", text(body, "executarEm", null)));
        LocalDateTime proxima = parseDateTime(text(body, "proxima_execucao", text(body, "proximaExecucao", null)));

        if (!List.of("GET", "POST", "PUT", "PATCH", "DELETE").contains(metodo)) {
            badRequest(resp, "metodo invalido");
            return;
        }
        if (!List.of("intervalo", "unico").contains(tipo)) {
            badRequest(resp, "tipo_agendamento invalido");
            return;
        }
        if ("intervalo".equals(tipo) && (intervalo == null || intervalo < 1)) {
            badRequest(resp, "intervalo_minutos deve ser maior ou igual a 1");
            return;
        }
        if ("unico".equals(tipo) && executarEm == null) {
            badRequest(resp, "executar_em obrigatorio para tarefa unica");
            return;
        }
        if (timeout < 1 || timeout > 300 || maxTentativas < 1 || maxTentativas > 20 || retryIntervalo < 1 || retryIntervalo > 1440) {
            badRequest(resp, "timeout, max_tentativas ou retry_intervalo_minutos fora do limite");
            return;
        }
        if (proxima == null) proxima = "unico".equals(tipo) ? executarEm : LocalDateTime.now().withSecond(0).withNano(0);

        String sql = """
            INSERT INTO cron.tarefa (
                nome, url, metodo, headers, payload, tipo_agendamento, intervalo_minutos,
                executar_em, proxima_execucao, timeout_segundos, max_tentativas, retry_intervalo_minutos
            ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?)
            RETURNING id
            """;
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, nome);
            stmt.setString(2, url);
            stmt.setString(3, metodo);
            stmt.setString(4, MAPPER.writeValueAsString(headers));
            stmt.setString(5, MAPPER.writeValueAsString(payload));
            stmt.setString(6, tipo);
            if (intervalo == null) stmt.setObject(7, null); else stmt.setInt(7, intervalo);
            if (executarEm == null) stmt.setObject(8, null); else stmt.setTimestamp(8, Timestamp.valueOf(executarEm));
            stmt.setTimestamp(9, Timestamp.valueOf(proxima));
            stmt.setInt(10, timeout);
            stmt.setInt(11, maxTentativas);
            stmt.setInt(12, retryIntervalo);
            try (ResultSet rs = stmt.executeQuery()) {
                rs.next();
                ObjectNode out = MAPPER.createObjectNode();
                out.put("ok", true);
                out.put("id", rs.getObject("id").toString());
                writeJson(resp, MAPPER.writeValueAsString(out));
            }
        }
    }

    private static void listTasks(HttpServletResponse resp) throws Exception {
        String sql = """
            SELECT id, nome, url, metodo, headers, payload, tipo_agendamento, intervalo_minutos,
                   executar_em, proxima_execucao, ultima_execucao, ativo, timeout_segundos,
                   max_tentativas, retry_intervalo_minutos, tentativas_feitas, criado_em, atualizado_em
            FROM cron.tarefa
            ORDER BY criado_em DESC
            LIMIT 200
            """;
        List<ObjectNode> tasks = new ArrayList<>();
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql); ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) tasks.add(taskJson(rs));
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("ok", true);
        out.set("tasks", MAPPER.valueToTree(tasks));
        writeJson(resp, MAPPER.writeValueAsString(out));
    }

    private static void listExecutions(String path, HttpServletResponse resp) throws Exception {
        String id = path.substring("/tasks/".length(), path.length() - "/executions".length());
        UUID taskId = UUID.fromString(id);
        listExecutions(resp, "WHERE tarefa_id = ?", taskId);
    }

    private static void listRecentExecutions(HttpServletResponse resp) throws Exception {
        listExecutions(resp, "", null);
    }

    private static void listExecutions(HttpServletResponse resp, String where, UUID taskId) throws Exception {
        String sql = """
            SELECT id, tarefa_id, iniciada_em, finalizada_em, duracao_ms, sucesso,
                   status_http, erro, resposta_body, resposta_headers, tentativa
            FROM cron.execucao
            %s
            ORDER BY iniciada_em DESC
            LIMIT 200
            """.formatted(where);
        List<ObjectNode> executions = new ArrayList<>();
        try (Connection conn = Db.getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            if (taskId != null) stmt.setObject(1, taskId);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) executions.add(executionJson(rs));
            }
        }
        ObjectNode out = MAPPER.createObjectNode();
        out.put("ok", true);
        out.set("executions", MAPPER.valueToTree(executions));
        writeJson(resp, MAPPER.writeValueAsString(out));
    }

    private static void runTask(String path, HttpServletResponse resp) throws Exception {
        String id = path.substring("/tasks/".length(), path.length() - "/run".length());
        TaskExecutor.ExecutionResult result = TaskExecutor.runNow(UUID.fromString(id));
        writeJson(resp, MAPPER.writeValueAsString(result));
    }

    private static ObjectNode taskJson(ResultSet rs) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", rs.getObject("id").toString());
        node.put("nome", rs.getString("nome"));
        node.put("url", rs.getString("url"));
        node.put("metodo", rs.getString("metodo"));
        node.set("headers", MAPPER.readTree(rs.getString("headers")));
        node.set("payload", MAPPER.readTree(rs.getString("payload")));
        node.put("tipo_agendamento", rs.getString("tipo_agendamento"));
        putNullableInt(node, "intervalo_minutos", rs, "intervalo_minutos");
        putNullableTimestamp(node, "executar_em", rs, "executar_em");
        putNullableTimestamp(node, "proxima_execucao", rs, "proxima_execucao");
        putNullableTimestamp(node, "ultima_execucao", rs, "ultima_execucao");
        node.put("ativo", rs.getBoolean("ativo"));
        node.put("timeout_segundos", rs.getInt("timeout_segundos"));
        node.put("max_tentativas", rs.getInt("max_tentativas"));
        node.put("retry_intervalo_minutos", rs.getInt("retry_intervalo_minutos"));
        node.put("tentativas_feitas", rs.getInt("tentativas_feitas"));
        putNullableTimestamp(node, "criado_em", rs, "criado_em");
        putNullableTimestamp(node, "atualizado_em", rs, "atualizado_em");
        return node;
    }

    private static ObjectNode executionJson(ResultSet rs) throws Exception {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("id", rs.getObject("id").toString());
        node.put("tarefa_id", rs.getObject("tarefa_id").toString());
        putNullableTimestamp(node, "iniciada_em", rs, "iniciada_em");
        putNullableTimestamp(node, "finalizada_em", rs, "finalizada_em");
        putNullableInt(node, "duracao_ms", rs, "duracao_ms");
        node.put("sucesso", rs.getBoolean("sucesso"));
        putNullableInt(node, "status_http", rs, "status_http");
        node.put("erro", rs.getString("erro"));
        node.put("resposta_body", rs.getString("resposta_body"));
        String headers = rs.getString("resposta_headers");
        if (headers == null) node.putNull("resposta_headers"); else node.set("resposta_headers", MAPPER.readTree(headers));
        node.put("tentativa", rs.getInt("tentativa"));
        return node;
    }

    private static JsonNode readBody(HttpServletRequest req) throws IOException {
        String body = new String(req.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (body.isBlank()) return MAPPER.createObjectNode();
        return MAPPER.readTree(body);
    }

    private static String requiredText(JsonNode node, String key) throws IOException {
        String value = text(node, key, null);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " obrigatorio");
        return value.trim();
    }

    private static String text(JsonNode node, String key, String fallback) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return fallback;
        return value.asText();
    }

    private static JsonNode objectValue(JsonNode node, String key) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return MAPPER.createObjectNode();
        if (!value.isObject()) throw new IllegalArgumentException(key + " deve ser objeto JSON");
        return value;
    }

    private static int intValue(JsonNode node, String key, int fallback) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return fallback;
        return value.asInt();
    }

    private static Integer nullableInt(JsonNode node, String key, Integer fallback) {
        JsonNode value = node == null ? null : node.get(key);
        if (value == null || value.isNull()) return fallback;
        return value.asInt();
    }

    private static LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
        return LocalDateTime.parse(value.trim(), DATE_TIME);
    }

    private static void putNullableTimestamp(ObjectNode node, String key, ResultSet rs, String column) throws Exception {
        Timestamp value = rs.getTimestamp(column);
        if (value == null) node.putNull(key); else node.put(key, value.toLocalDateTime().format(DATE_TIME));
    }

    private static void putNullableInt(ObjectNode node, String key, ResultSet rs, String column) throws Exception {
        int value = rs.getInt(column);
        if (rs.wasNull()) node.putNull(key); else node.put(key, value);
    }

    private static void badRequest(HttpServletResponse resp, String error) throws IOException {
        resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
        writeJson(resp, "{\"ok\":false,\"error\":\"" + escapeJson(error) + "\"}");
    }

    private static void notFound(HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
        writeJson(resp, "{\"ok\":false,\"error\":\"endpoint nao encontrado\"}");
    }

    private static void fail(HttpServletResponse resp, Exception ex) throws IOException {
        ex.printStackTrace(System.err);
        int status = ex instanceof IllegalArgumentException ? HttpServletResponse.SC_BAD_REQUEST : HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        resp.setStatus(status);
        writeJson(resp, "{\"ok\":false,\"error\":\"" + escapeJson(ex.getMessage()) + "\"}");
    }

    private static void writeJson(HttpServletResponse resp, String json) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resp.getWriter().write(json);
    }

    private static String escapeJson(String val) {
        if (val == null) return "";
        return val.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
