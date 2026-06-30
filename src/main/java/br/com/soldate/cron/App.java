package br.com.soldate.cron;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;

public class App {
    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getProperty("PORT", AppConfig.read("PORT", String.valueOf(AppDefaults.PORT))));

        CronWorker worker = new CronWorker();
        worker.start();
        Runtime.getRuntime().addShutdownHook(new Thread(worker::stop));

        Server server = new Server(port);
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");
        context.addServlet(new ServletHolder(new HealthServlet()), "/health");
        context.addServlet(new ServletHolder(new CronServlet()), "/api/cron/*");
        server.setHandler(context);

        server.start();
        server.join();
    }
}
