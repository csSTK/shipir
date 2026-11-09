package dev.bethge.shipir;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared test fixture for the setup gate: ADMIN requests are only allowed once ADMIN owns a docker host
 * (environment). Integration tests call this after they have created their users.
 */
public final class SetupGateFixture {

    private SetupGateFixture() {
    }

    /** Clears deployments and docker hosts, then gives the ADMIN user one LOCAL docker host called {@code hostName}. */
    public static void resetWithAdminHost(JdbcTemplate jdbc, String hostName) {
        jdbc.update("DELETE FROM deployments");
        jdbc.update("DELETE FROM docker_hosts");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT ?, 'LOCAL', id FROM users WHERE username = 'admin'", hostName);
    }
}
