package dev.bethge.shipir.permission;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import dev.bethge.shipir.auth.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Role x grant level x host/deployment grant matrix against the real schema. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PermissionServiceTest {

    @Autowired
    private PermissionService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserRepository users;
    @Autowired
    private PermissionEvaluator evaluator;

    private UUID adminId;
    private UUID hostA;
    private UUID hostB;
    private UUID depA1;
    private UUID depA2;
    private UUID depB1;

    @BeforeEach
    void data() {
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        adminId = users.findByUsername("admin").orElseThrow().getId();
        hostA = host("a");
        hostB = host("b");
        depA1 = deployment("a1", hostA);
        depA2 = deployment("a2", hostA);
        depB1 = deployment("b1", hostB);
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    }

    private UUID host(String name) {
        return jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "VALUES (?, 'LOCAL', ?) RETURNING id", UUID.class, name, adminId);
    }

    private UUID deployment(String name, UUID host) {
        return jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "VALUES (?, ?, 'x', ?) RETURNING id", UUID.class, name, host, adminId);
    }

    private void grantHost(UUID user, UUID host, PermissionLevel level) {
        jdbc.update("INSERT INTO permissions (user_id, host_id, level) VALUES (?, ?, ?)", user, host, level.name());
    }

    private void grantDeployment(UUID user, UUID dep, PermissionLevel level) {
        jdbc.update("INSERT INTO permissions (user_id, deployment_id, level) VALUES (?, ?, ?)", user, dep, level.name());
    }

    private PermissionSubject subject(Role role) {
        return new PermissionSubject(UUID.randomUUID(), role);
    }

    @Test
    void adminAlwaysAllowedWithoutGrants() {
        PermissionSubject admin = new PermissionSubject(adminId, Role.ADMIN);
        assertThat(service.canView(admin, hostA)).isTrue();
        assertThat(service.canOperate(admin, hostA)).isTrue();
        assertThat(service.canView(admin, hostA, depA1)).isTrue();
        assertThat(service.canOperate(admin, hostA, depA1)).isTrue();
        assertThat(service.hostFullyVisible(admin, hostB)).isTrue();
        assertThat(service.visibleHostIds(admin)).containsExactlyInAnyOrder(hostA, hostB);
        assertThat(service.visibleDeploymentIds(admin, hostA)).containsExactlyInAnyOrder(depA1, depA2);
    }

    @Test
    void withoutGrantsNonAdminsSeeNothing() {
        for (Role role : new Role[]{Role.OPERATOR, Role.VIEWER}) {
            PermissionSubject s = subject(role);
            assertThat(service.canView(s, hostA)).isFalse();
            assertThat(service.canOperate(s, hostA)).isFalse();
            assertThat(service.canView(s, hostA, depA1)).isFalse();
            assertThat(service.canOperate(s, hostA, depA1)).isFalse();
            assertThat(service.visibleHostIds(s)).isEmpty();
            assertThat(service.visibleDeploymentIds(s, hostA)).isEmpty();
        }
    }

    @Test
    void hostGrantLevelAndRoleCapMatrix() {
        record Case(Role role, PermissionLevel level, boolean view, boolean operate) {
        }
        for (Case c : new Case[]{
                new Case(Role.OPERATOR, PermissionLevel.VIEW, true, false),
                new Case(Role.OPERATOR, PermissionLevel.OPERATE, true, true),
                new Case(Role.VIEWER, PermissionLevel.VIEW, true, false),
                new Case(Role.VIEWER, PermissionLevel.OPERATE, true, false)}) {
            jdbc.update("DELETE FROM permissions");
            PermissionSubject s = subject(c.role());
            jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'x', ?)",
                    s.userId(), "u" + s.userId(), c.role().name());
            grantHost(s.userId(), hostA, c.level());

            String label = c.toString();
            assertThat(service.canView(s, hostA)).as(label).isTrue();
            assertThat(service.canOperate(s, hostA)).as(label).isEqualTo(c.operate());
            // a host grant covers every deployment of that host ...
            assertThat(service.canView(s, hostA, depA1)).as(label).isTrue();
            assertThat(service.canView(s, hostA, depA2)).as(label).isTrue();
            assertThat(service.canOperate(s, hostA, depA2)).as(label).isEqualTo(c.operate());
            assertThat(service.hostFullyVisible(s, hostA)).isTrue();
            assertThat(service.visibleDeploymentIds(s, hostA)).containsExactlyInAnyOrder(depA1, depA2);
            // ... and nothing on other hosts
            assertThat(service.canView(s, hostB)).as(label).isFalse();
            assertThat(service.canView(s, hostB, depB1)).as(label).isFalse();
            assertThat(service.visibleHostIds(s)).containsExactly(hostA);
        }
    }

    @Test
    void deploymentGrantLimitsToThatDeploymentButMakesHostVisible() {
        PermissionSubject s = subject(Role.OPERATOR);
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, 'op1', 'x', 'OPERATOR')", s.userId());
        grantDeployment(s.userId(), depA1, PermissionLevel.OPERATE);

        assertThat(service.canView(s, hostA)).isTrue();
        assertThat(service.hostFullyVisible(s, hostA)).isFalse();
        assertThat(service.canOperate(s, hostA)).isFalse();
        assertThat(service.canView(s, hostA, depA1)).isTrue();
        assertThat(service.canOperate(s, hostA, depA1)).isTrue();
        assertThat(service.canView(s, hostA, depA2)).isFalse();
        assertThat(service.canOperate(s, hostA, depA2)).isFalse();
        assertThat(service.visibleHostIds(s)).containsExactly(hostA);
        assertThat(service.visibleDeploymentIds(s, hostA)).containsExactly(depA1);
        assertThat(service.canView(s, hostB)).isFalse();
    }

    @Test
    void viewerDeploymentOperateGrantOnlyViews() {
        PermissionSubject s = subject(Role.VIEWER);
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, 'v1', 'x', 'VIEWER')", s.userId());
        grantDeployment(s.userId(), depA1, PermissionLevel.OPERATE);
        assertThat(service.canView(s, hostA, depA1)).isTrue();
        assertThat(service.canOperate(s, hostA, depA1)).isFalse();
    }

    @Test
    void betterOfHostAndDeploymentGrantApplies() {
        PermissionSubject s = subject(Role.OPERATOR);
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, 'op2', 'x', 'OPERATOR')", s.userId());
        grantHost(s.userId(), hostA, PermissionLevel.VIEW);
        grantDeployment(s.userId(), depA1, PermissionLevel.OPERATE);
        assertThat(service.canOperate(s, hostA, depA1)).isTrue();
        assertThat(service.canOperate(s, hostA, depA2)).isFalse();
        assertThat(service.canOperate(s, hostA)).isFalse();
    }

    @Test
    void deploymentIdOfAnotherHostIsNeverMatched() {
        PermissionSubject s = subject(Role.OPERATOR);
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, 'op3', 'x', 'OPERATOR')", s.userId());
        grantHost(s.userId(), hostA, PermissionLevel.OPERATE);
        // depB1 lives on hostB: pairing it with hostA must not leak access
        assertThat(service.canView(s, hostA, depB1)).isFalse();
        assertThat(service.canOperate(s, hostA, depB1)).isFalse();
        assertThat(service.canView(s, hostA, UUID.randomUUID())).isFalse();
        assertThat(service.visibleHostIds(s)).isEqualTo(Set.of(hostA));
    }

    @Test
    void evaluatorUsesSignedInUserAndDeniesAnonymous() {
        jdbc.update("INSERT INTO users (username, password_hash, role) VALUES ('evalop', 'x', 'OPERATOR')");
        UUID id = users.findByUsername("evalop").orElseThrow().getId();
        grantHost(id, hostA, PermissionLevel.OPERATE);
        try {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            assertThat(evaluator.canView(hostA)).isFalse();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.TestingAuthenticationToken("evalop", "x", "ROLE_OPERATOR"));
            assertThat(evaluator.canOperate(hostA)).isTrue();
            assertThat(evaluator.canOperate(hostA, depA1)).isTrue();
            assertThat(evaluator.canView(hostB)).isFalse();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.TestingAuthenticationToken("ghost", "x", "ROLE_ADMIN"));
            assertThat(evaluator.canView(hostA)).isFalse();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }
}
