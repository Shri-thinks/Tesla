package com.tesla.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class H2TcpServerTest {

    private static final Path DATABASE_DIRECTORY =
            Path.of("target", "h2-tcp-test-" + UUID.randomUUID()).toAbsolutePath().normalize();
    private static final int TCP_PORT = findAvailablePort();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:tcp://127.0.0.1:" + TCP_PORT + "/tesla;MODE=PostgreSQL");
        registry.add("tesla.database.h2.tcp.enabled", () -> true);
        registry.add("tesla.database.h2.tcp.port", () -> TCP_PORT);
        registry.add("tesla.database.h2.base-dir", () -> DATABASE_DIRECTORY.toString());
    }

    @Test
    void applicationAndDatabaseClientsCanUseTheSameTcpDatabase() throws Exception {
        String accountReference = "account_" + UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO accounts(account_reference, customer_reference, status, credit_limit, posted_balance)
                VALUES(?, 'dbeaver-test', 'OPEN', 250.00, 0.00)
                """, accountReference);

        try (var connection = java.sql.DriverManager.getConnection(
                "jdbc:h2:tcp://127.0.0.1:" + TCP_PORT + "/tesla;MODE=PostgreSQL", "sa", "");
             var statement = connection.prepareStatement(
                     "SELECT customer_reference FROM accounts WHERE account_reference = ?")) {
            statement.setString(1, accountReference);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("The TCP database did not return the inserted account.");
                }
                assertEquals("dbeaver-test", result.getString("customer_reference"));
            }
        }
    }

    private static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }
}
