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
        jdbc.update("DELETE FROM teams");
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
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    }

    private UUID host(String name) {
        return jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, host, port, owner_user_id) "
                + "VALUES (?, 'TCP', 'docker.local', 2375, ?) RETURNING id", UUID.class, name, adminId);
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

    private UUID team(String name, UUID... memberIds) {
        UUID id = jdbc.queryForObject("INSERT INTO teams (name) VALUES (?) RETURNING id", UUID.class, name);
        for (UUID m : memberIds) {
            jdbc.update("INSERT INTO team_members (team_id, user_id) VALUES (?, ?)", id, m);
        }
        return id;
    }

    private PermissionSubject persisted(Role role, String name) {
        PermissionSubject s = subject(role);
        jdbc.update("INSERT INTO users (id, username, password_hash, role) VALUES (?, ?, 'x', ?)",
                s.userId(), name, role.name());
        return s;
    }

    private void teamGrantHost(UUID team, UUID host, PermissionLevel level) {
        jdbc.update("INSERT INTO permissions (team_id, host_id, level) VALUES (?, ?, ?)", team, host, level.name());
    }

    private void teamGrantDeployment(UUID team, UUID dep, PermissionLevel level) {
        jdbc.update("INSERT INTO permissions (team_id, deployment_id, level) VALUES (?, ?, ?)", team, dep, level.name());
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

    @Test
    void teamHostGrantLevelAndRoleCapMatrix() {
        record Case(Role role, PermissionLevel level, boolean operate) {
        }
        int n = 0;
        for (Case c : new Case[]{
                new Case(Role.OPERATOR, PermissionLevel.VIEW, false),
                new Case(Role.OPERATOR, PermissionLevel.OPERATE, true),
                new Case(Role.VIEWER, PermissionLevel.VIEW, false),
                new Case(Role.VIEWER, PermissionLevel.OPERATE, false)}) {
            jdbc.update("DELETE FROM permissions");
            jdbc.update("DELETE FROM teams");
            PermissionSubject s = persisted(c.role(), "tm" + n++);
            UUID t = team("t" + n, s.userId());
            teamGrantHost(t, hostA, c.level());
            String label = c.toString();
            assertThat(service.canView(s, hostA)).as(label).isTrue();
            assertThat(service.canOperate(s, hostA)).as(label).isEqualTo(c.operate());
            assertThat(service.canView(s, hostA, depA1)).as(label).isTrue();
            assertThat(service.canOperate(s, hostA, depA2)).as(label).isEqualTo(c.operate());
            assertThat(service.hostFullyVisible(s, hostA)).isTrue();
            assertThat(service.visibleHostIds(s)).containsExactly(hostA);
            assertThat(service.visibleDeploymentIds(s, hostA)).containsExactlyInAnyOrder(depA1, depA2);
            assertThat(service.canView(s, hostB)).as(label).isFalse();
        }
    }

    @Test
    void teamDeploymentGrantLimitsToThatDeployment() {
        PermissionSubject s = persisted(Role.OPERATOR, "tmd");
        teamGrantDeployment(team("dev", s.userId()), depA1, PermissionLevel.OPERATE);
        assertThat(service.canView(s, hostA)).isTrue();
        assertThat(service.hostFullyVisible(s, hostA)).isFalse();
        assertThat(service.canOperate(s, hostA, depA1)).isTrue();
        assertThat(service.canView(s, hostA, depA2)).isFalse();
        assertThat(service.visibleHostIds(s)).containsExactly(hostA);
        assertThat(service.visibleDeploymentIds(s, hostA)).containsExactly(depA1);
    }

    @Test
    void bestLevelOverUserGrantAndSeveralTeamsApplies() {
        PermissionSubject s = persisted(Role.OPERATOR, "tmb");
        grantHost(s.userId(), hostA, PermissionLevel.VIEW);
        teamGrantHost(team("viewers", s.userId()), hostA, PermissionLevel.VIEW);
        assertThat(service.canOperate(s, hostA)).isFalse();
        teamGrantHost(team("operators", s.userId()), hostA, PermissionLevel.OPERATE);
        assertThat(service.canOperate(s, hostA)).isTrue();
        assertThat(service.canOperate(s, hostA, depA1)).isTrue();
        // a team VIEW host grant plus a deployment OPERATE grant of another team: best of both on that deployment
        PermissionSubject s2 = persisted(Role.OPERATOR, "tmb2");
        teamGrantHost(team("hv", s2.userId()), hostA, PermissionLevel.VIEW);
        teamGrantDeployment(team("do", s2.userId()), depA1, PermissionLevel.OPERATE);
        assertThat(service.canOperate(s2, hostA, depA1)).isTrue();
        assertThat(service.canOperate(s2, hostA, depA2)).isFalse();
    }

    @Test
    void teamGrantDoesNotReachNonMembersOrOtherTeams() {
        PermissionSubject member = persisted(Role.OPERATOR, "in");
        PermissionSubject outsider = persisted(Role.OPERATOR, "out");
        UUID granted = team("granted", member.userId());
        team("other", outsider.userId());
        teamGrantHost(granted, hostA, PermissionLevel.OPERATE);
        assertThat(service.canOperate(member, hostA)).isTrue();
        assertThat(service.canView(outsider, hostA)).isFalse();
        assertThat(service.visibleHostIds(outsider)).isEmpty();
    }

    @Test
    void removingTheMemberRevokesAccessImmediately() {
        PermissionSubject s = persisted(Role.OPERATOR, "rev");
        UUID t = team("crew", s.userId());
        teamGrantHost(t, hostA, PermissionLevel.OPERATE);
        assertThat(service.canOperate(s, hostA)).isTrue();
        jdbc.update("DELETE FROM team_members WHERE team_id = ? AND user_id = ?", t, s.userId());
        assertThat(service.canView(s, hostA)).isFalse();
        assertThat(service.canOperate(s, hostA, depA1)).isFalse();
        assertThat(service.visibleHostIds(s)).isEmpty();
    }

    @Test
    void deletingTheTeamRevokesAccessAndRemovesItsGrants() {
        PermissionSubject s = persisted(Role.VIEWER, "gone");
        UUID t = team("temp", s.userId());
        teamGrantHost(t, hostA, PermissionLevel.VIEW);
        jdbc.update("DELETE FROM teams WHERE id = ?", t);
        assertThat(service.canView(s, hostA)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions WHERE team_id IS NOT NULL", Long.class)).isZero();
    }

    @Test
    void evaluatorHonoursTeamGrants() {
        PermissionSubject s = persisted(Role.OPERATOR, "evalteam");
        teamGrantHost(team("evalt", s.userId()), hostA, PermissionLevel.OPERATE);
        try {
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.TestingAuthenticationToken("evalteam", "x", "ROLE_OPERATOR"));
            assertThat(evaluator.canOperate(hostA)).isTrue();
            assertThat(evaluator.canView(hostB)).isFalse();
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void canOperateAnyDeploymentMatrix() {
        PermissionSubject admin = new PermissionSubject(adminId, Role.ADMIN);
        assertThat(service.canOperateAnyDeployment(admin, hostA)).isTrue();

        PermissionSubject none = persisted(Role.OPERATOR, "anyNone");
        assertThat(service.canOperateAnyDeployment(none, hostA)).isFalse();

        // own deployment OPERATE grant: only on that host
        PermissionSubject own = persisted(Role.OPERATOR, "anyOwn");
        grantDeployment(own.userId(), depA1, PermissionLevel.OPERATE);
        assertThat(service.canOperateAnyDeployment(own, hostA)).isTrue();
        assertThat(service.canOperateAnyDeployment(own, hostB)).isFalse();

        // VIEW deployment grant never operates
        PermissionSubject view = persisted(Role.OPERATOR, "anyView");
        grantDeployment(view.userId(), depA1, PermissionLevel.VIEW);
        assertThat(service.canOperateAnyDeployment(view, hostA)).isFalse();

        // OPERATE grant on another host's deployment does not leak
        PermissionSubject other = persisted(Role.OPERATOR, "anyOther");
        grantDeployment(other.userId(), depB1, PermissionLevel.OPERATE);
        assertThat(service.canOperateAnyDeployment(other, hostA)).isFalse();
        assertThat(service.canOperateAnyDeployment(other, hostB)).isTrue();

        // host OPERATE grant covers the host's deployments, not other hosts
        PermissionSubject host = persisted(Role.OPERATOR, "anyHost");
        grantHost(host.userId(), hostA, PermissionLevel.OPERATE);
        assertThat(service.canOperateAnyDeployment(host, hostA)).isTrue();
        assertThat(service.canOperateAnyDeployment(host, hostB)).isFalse();

        // host VIEW grant does not operate
        PermissionSubject hostView = persisted(Role.OPERATOR, "anyHostView");
        grantHost(hostView.userId(), hostA, PermissionLevel.VIEW);
        assertThat(service.canOperateAnyDeployment(hostView, hostA)).isFalse();

        // role cap: VIEWER never operates
        PermissionSubject viewer = persisted(Role.VIEWER, "anyViewer");
        grantDeployment(viewer.userId(), depA1, PermissionLevel.OPERATE);
        grantHost(viewer.userId(), hostB, PermissionLevel.OPERATE);
        assertThat(service.canOperateAnyDeployment(viewer, hostA)).isFalse();
        assertThat(service.canOperateAnyDeployment(viewer, hostB)).isFalse();
    }

    @Test
    void canOperateAnyDeploymentHonoursTeamGrantsAndEmptyHosts() {
        PermissionSubject s = persisted(Role.OPERATOR, "anyTeam");
        UUID t = team("anyt", s.userId());
        teamGrantDeployment(t, depA2, PermissionLevel.OPERATE);
        assertThat(service.canOperateAnyDeployment(s, hostA)).isTrue();
        assertThat(service.canOperateAnyDeployment(s, hostB)).isFalse();

        PermissionSubject nonMember = persisted(Role.OPERATOR, "anyNonMember");
        assertThat(service.canOperateAnyDeployment(nonMember, hostA)).isFalse();

        // host without deployments: nothing to operate, even with an OPERATE host grant
        UUID emptyHost = host("empty");
        PermissionSubject h = persisted(Role.OPERATOR, "anyEmpty");
        grantHost(h.userId(), emptyHost, PermissionLevel.OPERATE);
        assertThat(service.canOperateAnyDeployment(h, emptyHost)).isFalse();
    }
}
