package dev.bethge.shipir;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ShipirApplicationTests {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void connectsToPostgres16() {
        String version = jdbcTemplate.queryForObject("SHOW server_version", String.class);
        assertThat(version).startsWith("16");
    }

    @Test
    void contextLoads() {
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("shipir");
    }
}
