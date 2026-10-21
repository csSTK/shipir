package dev.bethge.shipir;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Shared test baseline: a throwaway PostgreSQL 16 container wired in as the datasource.
 * Integration tests add {@code @Import(PostgresTestConfiguration.class)}. Needs a running Docker daemon.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }
}
