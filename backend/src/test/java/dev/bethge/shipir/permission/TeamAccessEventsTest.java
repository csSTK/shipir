package dev.bethge.shipir.permission;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.common.security.UserAccessChangedEvent;
import dev.bethge.shipir.permission.dto.GrantRequest;
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
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Team membership and team grant changes alter members' effective access, so live channels (log/event
 * subscriptions, exec consoles) must be re-authorized: every affected member gets a {@link UserAccessChangedEvent}.
 */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
@RecordApplicationEvents
class TeamAccessEventsTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TeamService teams;
    @Autowired
    private PermissionGrantService grants;
    @Autowired
    private ApplicationEvents events;

    private UUID host;
    private UUID alice;
    private UUID bob;
    private UUID team;

    @BeforeEach
    void data() {
        clean();
        UUID adminId = jdbc.queryForObject("SELECT id FROM users WHERE username = 'admin'", UUID.class);
        host = jdbc.queryForObject("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "VALUES ('h', 'LOCAL', ?) RETURNING id", UUID.class, adminId);
        alice = user("alice");
        bob = user("bob");
        team = teams.create("crew").id();
        teams.replaceMembers(team, List.of(alice, bob));
        events.clear();
    }

    @AfterEach
    void clean() {
        SecurityContextHolder.clearContext();
        jdbc.update("DELETE FROM audit_log");
        jdbc.update("DELETE FROM permissions");
        jdbc.update("DELETE FROM teams");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("DELETE FROM users WHERE username <> 'admin'");
    }

    private UUID user(String name) {
        return jdbc.queryForObject("INSERT INTO users (username, password_hash, role, password_changed_at) "
                + "VALUES (?, 'x', 'VIEWER', now()) RETURNING id", UUID.class, name);
    }

    private List<String> changed() {
        return events.stream(UserAccessChangedEvent.class).map(UserAccessChangedEvent::username).sorted().toList();
    }

    @Test
    void teamGrantReplacementNotifiesEveryMember() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        grants.replaceForTeam(team, List.of(new GrantRequest(host, null, PermissionLevel.VIEW)));
        assertThat(changed()).containsExactly("alice", "bob");
    }

    @Test
    void removingAMemberNotifiesOnlyThatMember() {
        teams.replaceMembers(team, List.of(alice));
        assertThat(changed()).containsExactly("bob");
    }

    @Test
    void addingAMemberNotifiesThatMember() {
        UUID carol = user("carol");
        teams.replaceMembers(team, List.of(alice, bob, carol));
        assertThat(changed()).containsExactly("carol");
    }

    @Test
    void deletingATeamNotifiesItsMembers() {
        teams.delete(team);
        assertThat(changed()).containsExactly("alice", "bob");
    }

    @Test
    void idpGroupSyncNotifiesTheUserOnlyWhenMembershipsChange() {
        teams.syncMemberships(alice, List.of("crew"));
        assertThat(changed()).isEmpty();
        teams.syncMemberships(alice, List.of());
        assertThat(changed()).containsExactly("alice");
    }
}
