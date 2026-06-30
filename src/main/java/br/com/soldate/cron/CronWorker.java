package br.com.soldate.cron;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class CronWorker {
    private final Thread thread;
    private volatile boolean running = true;

    public CronWorker() {
        this.thread = new Thread(this::loop, "cron-worker");
        this.thread.setDaemon(true);
    }

    public void start() {
        thread.start();
    }

    public void stop() {
        running = false;
        thread.interrupt();
    }

    private void loop() {
        while (running) {
            try {
                runDueTasks();
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
            }
            sleep();
        }
    }

    private void runDueTasks() throws Exception {
        List<TaskExecutor.Task> tasks = claimDueTasks();
        for (TaskExecutor.Task task : tasks) {
            try {
                TaskExecutor.execute(task);
            } catch (Exception ex) {
                ex.printStackTrace(System.err);
            }
        }
    }

    private List<TaskExecutor.Task> claimDueTasks() throws Exception {
        int batchSize = readInt("WORKER_BATCH_SIZE", AppDefaults.WORKER_BATCH_SIZE);
        String sql = """
            SELECT id, nome, url, metodo, headers::text, payload::text, tipo_agendamento,
                   intervalo_minutos, timeout_segundos, max_tentativas, retry_intervalo_minutos, tentativas_feitas
            FROM cron.tarefa
            WHERE ativo = true
              AND proxima_execucao IS NOT NULL
              AND proxima_execucao <= date_trunc('minute', now())
            ORDER BY proxima_execucao ASC
            LIMIT ?
            FOR UPDATE SKIP LOCKED
            """;
        List<TaskExecutor.Task> tasks = new ArrayList<>();
        try (Connection conn = Db.getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, batchSize);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) tasks.add(TaskExecutor.taskFromResult(rs));
                }
            }
            conn.commit();
        }
        return tasks;
    }

    private void sleep() {
        int seconds = readInt("WORKER_INTERVAL_SECONDS", AppDefaults.WORKER_INTERVAL_SECONDS);
        try {
            Thread.sleep(Math.max(1, seconds) * 1000L);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static int readInt(String key, int fallback) {
        try {
            return Integer.parseInt(AppConfig.read(key, String.valueOf(fallback)));
        } catch (Exception ex) {
            return fallback;
        }
    }
}
