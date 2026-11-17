package dev.bethge.shipir.permission;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.auth.Role;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class TeamApiTest {

    private static final String ADMIN_PASSWORD = "test-initial-admin-password";
    private static final String PW = "Some-Long-Passw0rd-123!";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private JsonMapper json;
    @Autowired
    private PermissionService permissions;
    @Autowired
    private TeamService teamService;
    @MockitoSpyBean
    private TeamRepository teamRepository;

    private UUID host;
    private UUID dep;
    private UUID operatorId;
    private UUID viewerId;

    @BeforeEach
    void data() {
        clean();
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        jdbc.update("UPDATE users SET password_hash = ?, password_changed_at = now() WHERE username = 'admin'",
                encoder.encode(ADMIN_PASSWORD));
        host = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "VALUES ('h', 'LOCAL', ?) RETURNING id", UUID.class, adminId);
        dep = jdbc.queryForObject("INSERT INTO deployments (name, host_id, compose_file_path, created_by) "
                + "VALUES ('d', ?, 'x', ?) RETURNING id", UUID.class, host, adminId);
        operatorId = user("oper", "OPERATOR");
        viewerId = user("viewer", "VIEWER");
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    }

    private UUID user(String name, String role) {
        return jdbc.queryForObject("INSERT INTO users (username, password_hash, role, password_changed_at) "
                + "VALUES (?, ?, ?, now()) RETURNING id", UUID.class, name, encoder.encode(PW), role);
    }

    private record Session(MockHttpSession session, Cookie csrf) {
    }

    private Session login(String user, String password) throws Exception {
        Cookie c = mvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
        MvcResult r = mvc.perform(post("/api/auth/login").cookie(c).header("X-XSRF-TOKEN", c.getValue())
                .contentType(APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", user, "password", password)))).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        return new Session((MockHttpSession) r.getRequest().getSession(false), c);
    }

    private ResultActions send(Session s, MockHttpServletRequestBuilder b, String body) throws Exception {
        b.session(s.session).cookie(s.csrf).header("X-XSRF-TOKEN", s.csrf.getValue());
        if (body != null) {
            b.contentType(APPLICATION_JSON).content(body);
        }
        return mvc.perform(b);
    }

    private UUID createTeam(Session admin, String name) throws Exception {
        String body = send(admin, post("/api/teams"), "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private String members(UUID... ids) {
        return "{\"userIds\":[" + String.join(",", java.util.Arrays.stream(ids).map(i -> "\"" + i + "\"").toList()) + "]}";
    }

    private long audit(String action, String outcome) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = ? AND outcome = ?", Long.class,
                action, outcome);
    }

    @Test
    void onlyAdminsMayUseTheTeamApi() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID team = createTeam(admin, "crew");
        for (Session s : new Session[]{login("oper", PW), login("viewer", PW)}) {
            send(s, get("/api/teams"), null).andExpect(status().isForbidden());
            send(s, post("/api/teams"), "{\"name\":\"x\"}").andExpect(status().isForbidden());
            send(s, get("/api/teams/" + team), null).andExpect(status().isForbidden());
            send(s, put("/api/teams/" + team), "{\"name\":\"x\"}").andExpect(status().isForbidden());
            send(s, delete("/api/teams/" + team), null).andExpect(status().isForbidden());
            send(s, put("/api/teams/" + team + "/members"), members(operatorId)).andExpect(status().isForbidden());
            send(s, put("/api/teams/" + team + "/permissions"), "{\"grants\":[]}").andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/teams")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams", Long.class)).isEqualTo(1);
    }

    @Test
    void writesRequireCsrf() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        mvc.perform(post("/api/teams").session(admin.session).cookie(admin.csrf)
                .contentType(APPLICATION_JSON).content("{\"name\":\"x\"}")).andExpect(status().isForbidden());
        UUID team = createTeam(admin, "crew");
        mvc.perform(delete("/api/teams/" + team).session(admin.session).cookie(admin.csrf))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/teams/" + team + "/members").session(admin.session).cookie(admin.csrf)
                .contentType(APPLICATION_JSON).content(members())).andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams", Long.class)).isEqualTo(1);
    }

    @Test
    void createListRenameDeleteWithAudit() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID team = createTeam(admin, "  Ops  ");
        send(admin, get("/api/teams"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Ops"))
                .andExpect(jsonPath("$[0].memberCount").value(0));
        send(admin, put("/api/teams/" + team), "{\"name\":\"Platform\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Platform"));
        send(admin, delete("/api/teams/" + team), null).andExpect(status().isNoContent());
        send(admin, get("/api/teams/" + team), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TEAM_NOT_FOUND"));
        assertThat(audit("TEAM_CREATE", "SUCCESS")).isEqualTo(1);
        assertThat(audit("TEAM_UPDATE", "SUCCESS")).isEqualTo(1);
        assertThat(audit("TEAM_DELETE", "SUCCESS")).isEqualTo(1);
    }

    @Test
    void racingDuplicateNameIsStill409WhenThePreCheckMisses() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        createTeam(admin, "Ops");
        UUID other = createTeam(admin, "Dev");
        // simulates the loser of a race: the existence pre-checks see nothing, the DB index decides
        doReturn(false).when(teamRepository).existsByNameIgnoreCase(anyString());
        doReturn(false).when(teamRepository).existsByNameIgnoreCaseAndIdNot(anyString(), any());
        try {
            send(admin, post("/api/teams"), "{\"name\":\"ops\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEAM_NAME_TAKEN"));
            send(admin, put("/api/teams/" + other), "{\"name\":\"OPS\"}").andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("TEAM_NAME_TAKEN"));
        } finally {
            org.mockito.Mockito.reset(teamRepository);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams", Long.class)).isEqualTo(2);
    }

    @Test
    void validatesNames() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        createTeam(admin, "Ops");
        send(admin, post("/api/teams"), "{\"name\":\"ops\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TEAM_NAME_TAKEN"));
        send(admin, post("/api/teams"), "{\"name\":\"   \"}").andExpect(status().isBadRequest());
        send(admin, post("/api/teams"), "{}").andExpect(status().isBadRequest());
        UUID other = createTeam(admin, "Dev");
        send(admin, put("/api/teams/" + other), "{\"name\":\"OPS\"}").andExpect(status().isConflict());
        send(admin, put("/api/teams/" + other), "{\"name\":\"Dev\"}").andExpect(status().isOk());
        assertThat(audit("TEAM_CREATE", "FAILURE")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void membersAreReplacedAndListed() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID team = createTeam(admin, "crew");
        send(admin, put("/api/teams/" + team + "/members"), members(operatorId, viewerId, operatorId))
                .andExpect(status().isOk()).andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[0].username").value("oper"));
        send(admin, get("/api/teams"), null).andExpect(jsonPath("$[0].memberCount").value(2));
        send(admin, put("/api/teams/" + team + "/members"), members(viewerId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].userId").value(viewerId.toString()));
        send(admin, put("/api/teams/" + team + "/members"), members(UUID.randomUUID()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_USER"));
        send(admin, put("/api/teams/" + UUID.randomUUID() + "/members"), members())
                .andExpect(status().isNotFound());
        assertThat(audit("TEAM_MEMBERS_UPDATE", "SUCCESS")).isEqualTo(2);
    }

    @Test
    void teamGrantsAreReplacedValidatedAndAudited() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID team = createTeam(admin, "crew");
        send(admin, get("/api/teams/" + team + "/permissions"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.grants.length()").value(0));
        send(admin, put("/api/teams/" + team + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"level\":\"VIEW\"},"
                        + "{\"deploymentId\":\"" + dep + "\",\"level\":\"OPERATE\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.grants.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions WHERE team_id = ? AND user_id IS NULL",
                Long.class, team)).isEqualTo(2);
        send(admin, put("/api/teams/" + team + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"level\":\"OPERATE\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.grants.length()").value(1));
        send(admin, put("/api/teams/" + team + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"deploymentId\":\"" + dep + "\",\"level\":\"VIEW\"}]}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_GRANT"));
        send(admin, put("/api/teams/" + UUID.randomUUID() + "/permissions"), "{\"grants\":[]}")
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions WHERE team_id = ?", Long.class, team))
                .isEqualTo(1);
        assertThat(audit("TEAM_PERMISSIONS_UPDATE", "SUCCESS")).isEqualTo(2);
        assertThat(audit("TEAM_PERMISSIONS_UPDATE", "FAILURE")).isGreaterThanOrEqualTo(1);
    }

    @Test
    void removingAMemberViaApiRevokesAccessImmediately() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID team = createTeam(admin, "crew");
        send(admin, put("/api/teams/" + team + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"level\":\"OPERATE\"}]}").andExpect(status().isOk());
        PermissionSubject op = new PermissionSubject(operatorId, Role.OPERATOR);
        assertThat(permissions.canView(op, host)).isFalse();

        send(admin, put("/api/teams/" + team + "/members"), members(operatorId)).andExpect(status().isOk());
        assertThat(permissions.canOperate(op, host)).isTrue();

        send(admin, put("/api/teams/" + team + "/members"), members()).andExpect(status().isOk());
        assertThat(permissions.canView(op, host)).isFalse();
        assertThat(permissions.canOperate(op, host, dep)).isFalse();
    }

    @Test
    void deletingATeamCascadesMembersAndGrants() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID team = createTeam(admin, "crew");
        send(admin, put("/api/teams/" + team + "/members"), members(operatorId)).andExpect(status().isOk());
        send(admin, put("/api/teams/" + team + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"level\":\"VIEW\"}]}").andExpect(status().isOk());
        send(admin, delete("/api/teams/" + team), null).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM team_members", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions", Long.class)).isZero();
        assertThat(permissions.canView(new PermissionSubject(operatorId, Role.OPERATOR), host)).isFalse();
    }

    @Test
    void syncMembershipsMatchesGroupNamesExactlyAndIgnoresUnknown() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID ops = createTeam(admin, "ops");
        UUID dev = createTeam(admin, "dev");
        UUID qa = createTeam(admin, "qa");
        send(admin, put("/api/teams/" + qa + "/members"), members(operatorId)).andExpect(status().isOk());

        teamService.syncMemberships(operatorId, List.of("ops", "dev", "Dev", "no-such-group"));
        assertThat(memberTeams(operatorId)).containsExactlyInAnyOrder(ops, dev);

        teamService.syncMemberships(operatorId, List.of("dev"));
        assertThat(memberTeams(operatorId)).containsExactly(dev);

        teamService.syncMemberships(operatorId, List.of());
        assertThat(memberTeams(operatorId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM teams", Long.class)).isEqualTo(3);
    }

    @Test
    void userTeamsListsOnlyTheUsersTeamsWithNamedGrants() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        UUID crew = createTeam(admin, "crew");
        UUID other = createTeam(admin, "other");
        UUID empty = createTeam(admin, "alpha");
        send(admin, put("/api/teams/" + crew + "/members"), members(operatorId, viewerId)).andExpect(status().isOk());
        send(admin, put("/api/teams/" + empty + "/members"), members(operatorId)).andExpect(status().isOk());
        send(admin, put("/api/teams/" + other + "/members"), members(viewerId)).andExpect(status().isOk());
        send(admin, put("/api/teams/" + crew + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"level\":\"VIEW\"},"
                        + "{\"deploymentId\":\"" + dep + "\",\"level\":\"OPERATE\"}]}").andExpect(status().isOk());
        send(admin, put("/api/teams/" + other + "/permissions"),
                "{\"grants\":[{\"hostId\":\"" + host + "\",\"level\":\"OPERATE\"}]}").andExpect(status().isOk());

        String body = send(admin, get("/api/users/" + operatorId + "/teams"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.teams.length()").value(2))
                .andExpect(jsonPath("$.teams[0].name").value("alpha"))
                .andExpect(jsonPath("$.teams[0].grants.length()").value(0))
                .andExpect(jsonPath("$.teams[1].id").value(crew.toString()))
                .andExpect(jsonPath("$.teams[1].name").value("crew"))
                .andExpect(jsonPath("$.teams[1].grants.length()").value(2))
                .andExpect(jsonPath("$.teams[1].grants[?(@.hostId == '" + host + "')].hostName").value("h"))
                .andExpect(jsonPath("$.teams[1].grants[?(@.hostId == '" + host + "')].level").value("VIEW"))
                .andExpect(jsonPath("$.teams[1].grants[?(@.deploymentId == '" + dep + "')].deploymentName").value("d"))
                .andExpect(jsonPath("$.teams[1].grants[?(@.deploymentId == '" + dep + "')].level").value("OPERATE"))
                .andReturn().getResponse().getContentAsString();
        // no other teams, no members, no user data
        assertThat(body).doesNotContain("other").doesNotContain("viewer").doesNotContain("members")
                .doesNotContain("password").doesNotContain(viewerId.toString());
    }

    @Test
    void userWithoutTeamsGetsAnEmptyList() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        createTeam(admin, "crew");
        send(admin, get("/api/users/" + operatorId + "/teams"), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.teams.length()").value(0));
    }

    @Test
    void userTeamsUnknownUserIs404AndNonAdminsAreForbidden() throws Exception {
        Session admin = login("admin", ADMIN_PASSWORD);
        send(admin, get("/api/users/" + UUID.randomUUID() + "/teams"), null).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        for (Session s : new Session[]{login("oper", PW), login("viewer", PW)}) {
            send(s, get("/api/users/" + operatorId + "/teams"), null).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/users/" + operatorId + "/teams")).andExpect(status().isUnauthorized());
    }

    private List<UUID> memberTeams(UUID userId) {
        return jdbc.queryForList("SELECT team_id FROM team_members WHERE user_id = ?", UUID.class, userId);
    }
}
