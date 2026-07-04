package br.com.soldate.cron;

import com.zaxxer.hikari.HikariPoolMXBean;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;

public class HealthServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        Debug.beginRequest(req);
        try {
            resp.setContentType("application/json");
            try (Connection conn = Db.getConnection()) {
                if (conn != null && !conn.isClosed()) {
                    HikariPoolMXBean pool = Db.getPoolMxBean();
                    int active = pool == null ? -1 : pool.getActiveConnections();
                    int idle = pool == null ? -1 : pool.getIdleConnections();
                    int total = pool == null ? -1 : pool.getTotalConnections();
                    int awaiting = pool == null ? -1 : pool.getThreadsAwaitingConnection();
                    resp.setStatus(HttpServletResponse.SC_OK);
                    resp.getWriter().write("""
                        {
                          "status": "ok",
                          "db": "ok",
                          "pool": {
                            "active": %d,
                            "idle": %d,
                            "total": %d,
                            "awaiting": %d
                          }
                        }
                        """.formatted(active, idle, total, awaiting));
                    return;
                }
            } catch (SQLException ex) {
                JavaErrorLog.save(req, ex);
                resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                resp.getWriter().write("{\"status\":\"error\",\"db\":\"fail\"}");
                return;
            }
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            resp.getWriter().write("{\"status\":\"error\",\"db\":\"fail\"}");
        } finally {
            Debug.endRequest();
        }
    }
}
