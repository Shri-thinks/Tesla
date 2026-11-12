package com.tesla.config;

import org.h2.tools.Server;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;

@Component("h2TcpServer")
public class H2TcpServer implements InitializingBean, DisposableBean {

    private final String databaseMode;
    private final boolean enabled;
    private final int port;
    private final Path baseDirectory;
    private Server server;

    public H2TcpServer(
            @Value("${tesla.database.mode:h2}") String databaseMode,
            @Value("${tesla.database.h2.tcp.enabled:true}") boolean enabled,
            @Value("${tesla.database.h2.tcp.port:9123}") int port,
            @Value("${tesla.database.h2.base-dir:./data}") String baseDirectory) {
        this.databaseMode = databaseMode;
        this.enabled = enabled;
        this.port = port;
        this.baseDirectory = Path.of(baseDirectory).toAbsolutePath().normalize();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        if (!enabled || !databaseMode.equalsIgnoreCase("h2")) {
            return;
        }
        Files.createDirectories(baseDirectory);
        try {
            server = Server.createTcpServer(
                    "-tcpPort", Integer.toString(port),
                    "-baseDir", baseDirectory.toString(),
                    "-ifNotExists").start();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not start the local H2 database server on port " + port + ".",
                    exception);
        }
    }

    @Override
    public void destroy() {
        if (server != null) {
            server.stop();
        }
    }
}
