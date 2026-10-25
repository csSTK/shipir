package dev.bethge.shipir.host;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Environment tags on create, rename and {@code PUT /api/hosts/{id}/tags}. Most hosts are inserted with SQL (TCP,
 * never contacted) so the tests do not depend on a Docker daemon; the create and overview cases use the local socket.
 */
class HostTagApiTest extends HostApiTestBase {

    private static final String LOCAL = "{\"name\":\"%s\",\"connectionType\":\"LOCAL\"%s}";

    @BeforeEach
    void clearAudit() {
        jdbc.update("DELETE FROM audit_log");
    }

    private String insertTcpHost(String name) {
        return jdbc.queryForObject("""
                INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted,
                    tls_key_encrypted, owner_user_id)
                SELECT ?, 'TCP', 'docker.invalid', 2376, '\\x01'::bytea, '\\x01'::bytea, '\\x01'::bytea, id
                FROM users WHERE username = 'admin' RETURNING id::text
                """, String.class, name);
    }

    private java.util.List<String> storedTags(String hostId) {
        return jdbc.queryForList("SELECT tag FROM host_tags WHERE host_id = ?::uuid ORDER BY tag", String.class,
                hostId);
    }

    private long hostUpdateAudits(String hostId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'HOST_UPDATE' AND target_id = ?",
                Long.class, hostId);
    }

    @Test
    void createStoresNormalisedTagsAndReturnsThemSorted() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local", ",\"tags\":[\"staging\",\" Prod \",\"prod\"]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags[0]").value("prod"))
                .andExpect(jsonPath("$.tags[1]").value("staging"))
                .andExpect(jsonPath("$.tags.length()").value(2));
        String id = jdbc.queryForObject("SELECT id::text FROM docker_hosts WHERE name = 'Local'", String.class);
        assertThat(storedTags(id)).containsExactly("prod", "staging");
    }

    @Test
    void createWithoutTagsReturnsEmptyList() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.tags").isEmpty());
    }

    @Test
    void createWithInvalidOrTooManyTagsIsBadRequestAndSavesNothing() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local", ",\"tags\":[\"not valid\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TAG"));
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local", ",\"tags\":[\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\","
                                + "\"t7\",\"t8\",\"t9\",\"t10\",\"t11\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_TAGS"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM host_tags", Integer.class)).isZero();
    }

    @Test
    void putTagsReplacesTheWholeSet() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"prod\",\"eu\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.tags[0]").value("eu"))
                .andExpect(jsonPath("$.tags[1]").value("prod"));
        assertThat(storedTags(id)).containsExactly("eu", "prod");

        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"staging\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("staging"));
        assertThat(storedTags(id)).containsExactly("staging");

        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").isEmpty());
        assertThat(storedTags(id)).isEmpty();
    }

    @Test
    void putTagsNormalisesAndDedupes() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"  PROD \",\"prod\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(1))
                .andExpect(jsonPath("$.tags[0]").value("prod"));
    }

    @Test
    void putTagsAcceptsExactlyTenDistinctTags() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"t0\",\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tags.length()").value(10));
    }

    @Test
    void putTagsWithElevenTagsIsBadRequestAndKeepsTheOldSet() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        jdbc.update("INSERT INTO host_tags (host_id, tag) VALUES (?::uuid, 'keep')", id);
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"t0\",\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\",\"t10\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TOO_MANY_TAGS"));
        assertThat(storedTags(id)).containsExactly("keep");
    }

    @Test
    void putTagsWithInvalidNameIsBadRequestAndKeepsTheOldSet() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        jdbc.update("INSERT INTO host_tags (host_id, tag) VALUES (?::uuid, 'keep')", id);
        for (String bad : new String[]{"-leading", "has space", "under_score", "UPPER*", "a".repeat(25)}) {
            mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                            .content("{\"tags\":[\"ok\",\"" + bad + "\"]}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TAG"));
        }
        assertThat(storedTags(id)).containsExactly("keep");
    }

    @Test
    void putTagsWithoutTagsFieldIsBadRequest() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putTagsForUnknownHostIsNotFound() throws Exception {
        Login admin = login("admin");
        insertTcpHost("edge"); // at least one environment, otherwise the setup gate answers first
        mvc.perform(admin.write(put("/api/hosts/00000000-0000-0000-0000-000000000001/tags"))
                        .contentType(APPLICATION_JSON).content("{\"tags\":[\"prod\"]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @Test
    void renameWithTagsUpdatesBothAndRenameWithoutTagsKeepsThem() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"tags\":[\"edge\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"))
                .andExpect(jsonPath("$.tags[0]").value("edge"));
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed again\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed again"))
                .andExpect(jsonPath("$.tags[0]").value("edge"));
        assertThat(storedTags(id)).containsExactly("edge");
    }

    @Test
    void renameWithInvalidTagsChangesNeitherNameNorTags() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        jdbc.update("INSERT INTO host_tags (host_id, tag) VALUES (?::uuid, 'keep')", id);
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Other\",\"tags\":[\"bad tag\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TAG"));
        assertThat(jdbc.queryForObject("SELECT name FROM docker_hosts WHERE id = ?::uuid", String.class, id))
                .isEqualTo("edge");
        assertThat(storedTags(id)).containsExactly("keep");
    }

    @Test
    void operatorAndViewerCannotSetTags() throws Exception {
        String id = insertTcpHost("edge");
        for (String name : new String[]{"operator", "viewer"}) {
            Login l = login(name);
            mvc.perform(l.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                            .content("{\"tags\":[\"prod\"]}"))
                    .andExpect(status().isForbidden());
        }
        assertThat(storedTags(id)).isEmpty();
    }

    @Test
    void settingTagsWithoutCsrfTokenIsForbidden() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(put("/api/hosts/" + id + "/tags").session(admin.session()).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"prod\"]}"))
                .andExpect(status().isForbidden());
        assertThat(storedTags(id)).isEmpty();
    }

    @Test
    void successfulTagChangeIsAuditedAsHostUpdateWithTheTags() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"prod\"]}"))
                .andExpect(status().isOk());
        assertThat(hostUpdateAudits(id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE action = 'HOST_UPDATE' AND target_id = ?",
                String.class, id)).isEqualTo("SUCCESS");
        assertThat(jdbc.queryForObject("SELECT details::text FROM audit_log WHERE action = 'HOST_UPDATE' "
                + "AND target_id = ?", String.class, id)).contains("prod");
    }

    @Test
    void rejectedTagChangeIsAuditedAsFailureOnly() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(put("/api/hosts/" + id + "/tags")).contentType(APPLICATION_JSON)
                        .content("{\"tags\":[\"bad tag\"]}"))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'HOST_UPDATE' "
                + "AND target_id = ? AND outcome = 'SUCCESS'", Long.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT outcome FROM audit_log WHERE action = 'HOST_UPDATE' AND target_id = ?",
                String.class, id)).isEqualTo("FAILURE");
    }

    @Test
    void patchWithTagsAuditsTheTagsOnTheRenameRow() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\",\"tags\":[\"edge\",\"eu\"]}"))
                .andExpect(status().isOk());
        assertThat(auditDetails("HOST_RENAME", id)).contains("edge").contains("eu");
    }

    @Test
    void createWithTagsAuditsTheTagsOnTheCreateRow() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local", ",\"tags\":[\"prod\"]")))
                .andExpect(status().isCreated());
        String id = jdbc.queryForObject("SELECT id::text FROM docker_hosts WHERE name = 'Local'", String.class);
        assertThat(auditDetails("HOST_CREATE", id)).contains("prod");
    }

    @Test
    void patchWithoutTagsKeepsTheTagsInTheRenameRowUnchanged() throws Exception {
        Login admin = login("admin");
        String id = insertTcpHost("edge");
        jdbc.update("INSERT INTO host_tags (host_id, tag) VALUES (?::uuid, 'keep')", id);
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk());
        assertThat(auditDetails("HOST_RENAME", id)).contains("keep");
        assertThat(storedTags(id)).containsExactly("keep");
    }

    private String auditDetails(String action, String targetId) {
        return jdbc.queryForObject("SELECT COALESCE(details::text, '') FROM audit_log WHERE action = ? AND target_id = ?",
                String.class, action, targetId);
    }

    @Test
    void tagsAreReturnedInHostListAndOverview() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local", ",\"tags\":[\"prod\"]")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/hosts").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tags[0]").value("prod"));
        mvc.perform(get("/api/hosts/overview").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tags[0]").value("prod"));
    }
}
