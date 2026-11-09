package dev.bethge.shipir.permission;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Reading a user's grants resolves the host of every deployment grant in a constant number of queries. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.session_factory.statement_inspector="
        + "dev.bethge.shipir.permission.PermissionGrantQueryCountTest$DeploymentSelectCounter")
@Import(PostgresTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class PermissionGrantQueryCountTest {

    @Autowired
    private PermissionGrantService service;
    @Autowired
    private JdbcTemplate jdbc;

    private UUID userId;
    private UUID hostId;

    @BeforeEach
    void data() {
        clean();
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        hostId = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "VALUES ('h', 'LOCAL', ?) RETURNING id", UUID.class, adminId);
        userId = jdbc.queryForObject("INSERT INTO users (username, password_hash, role, password_changed_at) "
                + "VALUES ('qc-oper', 'x', 'OPERATOR', now()) RETURNING id", UUID.class);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username = 'qc-oper'");
    }

    private void grantDeployments(int count) {
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        for (int i = 0; i < count; i++) {
            UUID dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                    + "VALUES (?, ?, 'x', ?) RETURNING id", UUID.class, "d" + i + "-" + UUID.randomUUID(), hostId, adminId);
            jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, 'OPERATE')", userId, dep);
        }
    }

    /** Counts the SQL statements that read the deployments table. */
    public static class DeploymentSelectCounter implements StatementInspector {
        static final AtomicLong COUNT = new AtomicLong();

        @Override
        public String inspect(String sql) {
            if (sql.toLowerCase(Locale.ROOT).contains("from deployments")) {
                COUNT.incrementAndGet();
            }
            return sql;
        }
    }

    private long deploymentSelectsForGet() {
        DeploymentSelectCounter.COUNT.set(0);
        var response = service.get(userId);
        long count = DeploymentSelectCounter.COUNT.get();
        assertThat(response.grants()).allSatisfy(g -> assertThat(g.deploymentHostId()).isEqualTo(hostId));
        return count;
    }

    @Test
    void queryCountDoesNotGrowWithTheNumberOfDeploymentGrants() {
        grantDeployments(1);
        long one = deploymentSelectsForGet();
        grantDeployments(5);
        long six = deploymentSelectsForGet();
        assertThat(one).isPositive();
        assertThat(six).isEqualTo(one);
    }
}
