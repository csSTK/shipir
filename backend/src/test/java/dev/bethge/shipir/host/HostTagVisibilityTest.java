package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Tags follow host visibility: a restricted OPERATOR or VIEWER only sees the tags of hosts they hold a grant on. */
class HostTagVisibilityTest extends HostApiTestBase {

    @Override
    protected boolean autoGrantHosts() {
        return false;
    }

    private String tcpHost(String name) {
        return jdbc.queryForObject("""
                INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted,
                    tls_key_encrypted, owner_user_id)
                SELECT ?, 'TCP', 'docker.invalid', 2376, '\\x01'::bytea, '\\x01'::bytea, '\\x01'::bytea, id
                FROM users WHERE username = 'admin' RETURNING id::text
                """, String.class, name);
    }

    @Test
    void operatorAndViewerSeeTagsOnlyOfHostsTheyCanSee() throws Exception {
        String visible = tcpHost("visible");
        String hidden = tcpHost("hidden");
        jdbc.update("INSERT INTO host_tags (host_id, tag) VALUES (?::uuid, 'shown')", visible);
        jdbc.update("INSERT INTO host_tags (host_id, tag) VALUES (?::uuid, 'secret')", hidden);
        for (String name : List.of("operator", "viewer")) {
            grantHost(name, UUID.fromString(visible), "VIEW");
            Login l = login(name);
            mvc.perform(get("/api/hosts").session(l.session())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(visible))
                    .andExpect(jsonPath("$[0].tags[0]").value("shown"));
            mvc.perform(get("/api/hosts/overview").session(l.session())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].id").value(visible))
                    .andExpect(jsonPath("$[0].tags[0]").value("shown"));
        }
    }
}
