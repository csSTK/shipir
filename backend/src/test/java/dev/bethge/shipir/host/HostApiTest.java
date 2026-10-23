package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Needs a Docker daemon: LOCAL connection tests run against it. Encryption key is configured here. */
@TestPropertySource(properties = "shipir.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=")
@ExtendWith(OutputCaptureExtension.class)
class HostApiTest extends HostApiTestBase {

    private static final String LOCAL = "{\"name\":\"%s\",\"connectionType\":\"LOCAL\"}";

    private String createLocal(Login admin, String name) throws Exception {
        MvcResult r = mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                .content(LOCAL.formatted(name)))
                .andExpect(status().isCreated()).andReturn();
        return com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void adminCreatesListsRenamesAndDeletesLocalHost() throws Exception {
        Login admin = login("admin");

        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON).content(LOCAL.formatted("Local")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Local"))
                .andExpect(jsonPath("$.connectionType").value("LOCAL"))
                .andExpect(jsonPath("$.tls").value(false));
        String id = jdbc.queryForObject("SELECT id::text FROM docker_hosts WHERE name = 'Local'", String.class);
        assertThat(jdbc.queryForObject("SELECT u.username FROM docker_hosts h JOIN users u ON u.id = h.owner_user_id",
                String.class)).isEqualTo("admin");

        mvc.perform(get("/api/hosts").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Local"));

        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed"));

        mvc.perform(admin.write(delete("/api/hosts/" + id))).andExpect(status().isNoContent());
        // no environment left: the admin is back at the ENVIRONMENT setup step
        mvc.perform(get("/api/hosts").session(admin.session()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SETUP_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
    }

    @Test
    void connectionTestAgainstLocalDockerSucceeds() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts/connection-test")).contentType(APPLICATION_JSON)
                        .content("{\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.dockerVersion").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
    }

    @Test
    void connectionTestAcceptsOptionalName() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts/connection-test")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"ignored\",\"connectionType\":\"LOCAL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void connectionTestReportsUnreachableTcpHostWithoutFailingTheRequest() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts/connection-test")).contentType(APPLICATION_JSON)
                        .content(tcpBody("x", "127.0.0.1", 1, pems())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.errorCode").isNotEmpty());
    }

    @Test
    void duplicateNameIsConflict() throws Exception {
        Login admin = login("admin");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, owner_user_id) "
                + "SELECT 'Taken', 'LOCAL', id FROM users WHERE username = 'admin'");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON).content(LOCAL.formatted("Taken")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOST_NAME_TAKEN"));
    }

    @Test
    void secondLocalHostIsConflict() throws Exception {
        Login admin = login("admin");
        createLocal(admin, "Local");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON).content(LOCAL.formatted("Local 2")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOCAL_HOST_EXISTS"));
    }

    @Test
    void renameToExistingNameIsConflict() throws Exception {
        Login admin = login("admin");
        String id = createLocal(admin, "Local");
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted, "
                + "tls_key_encrypted, owner_user_id) SELECT 'Other', 'TCP', 'h', 2376, '\\x01'::bytea, '\\x01'::bytea, "
                + "'\\x01'::bytea, id FROM users WHERE username = 'admin'");
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"Other\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("HOST_NAME_TAKEN"));
    }

    @Test
    void unknownHostIsNotFound() throws Exception {
        Login admin = login("admin");
        createLocal(admin, "Existing"); // without any environment the setup gate would answer 403
        String id = "00000000-0000-0000-0000-000000000001";
        mvc.perform(admin.write(delete("/api/hosts/" + id))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
        mvc.perform(admin.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON).content("{\"name\":\"a\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidInputIsBadRequestWithFieldDetails() throws Exception {
        Login admin = login("admin");
        String[] pems = pems();
        // blank name, missing host, port out of range, missing key
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\" \",\"connectionType\":\"TCP\",\"port\":70000,\"tlsCaPem\":\"x\",\"tlsCertPem\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.details[?(@ =~ /^name:.*/)]").isNotEmpty())
                .andExpect(jsonPath("$.details[?(@ =~ /^port:.*/)]").isNotEmpty());
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"connectionType\":\"TCP\",\"port\":2376,\"tlsCaPem\":\"x\",\"tlsCertPem\":\"y\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@ =~ /^host:.*/)]").isNotEmpty())
                .andExpect(jsonPath("$.details[?(@ =~ /^tlsKeyPem:.*/)]").isNotEmpty());
        // LOCAL takes no TCP fields
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"l\",\"connectionType\":\"LOCAL\",\"host\":\"h\",\"port\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@ =~ /^host:.*/)]").isNotEmpty());
        // name too long
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("n".repeat(101))))
                .andExpect(status().isBadRequest());
        // oversized PEM
        String big = "A".repeat(70_000);
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"connectionType\":\"TCP\",\"host\":\"h\",\"port\":2376,\"tlsCaPem\":\"%s\",\"tlsCertPem\":\"y\",\"tlsKeyPem\":\"z\"}".formatted(big)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details[?(@ =~ /^tlsCaPem:.*/)]").isNotEmpty());
        assertThat(pems).hasSize(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
    }

    @Test
    void unusablePemIsBadRequestBeforeAnyConnection() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content("{\"name\":\"t\",\"connectionType\":\"TCP\",\"host\":\"127.0.0.1\",\"port\":1,"
                                + "\"tlsCaPem\":\"nope\",\"tlsCertPem\":\"nope\",\"tlsKeyPem\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TLS_MATERIAL"));
    }

    @Test
    void unreachableTcpHostIsUnprocessableAndNothingIsSaved() throws Exception {
        Login admin = login("admin");
        mvc.perform(admin.write(post("/api/hosts")).contentType(APPLICATION_JSON)
                        .content(tcpBody("Remote", "127.0.0.1", 1, pems())))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CONNECTION_FAILED"))
                .andExpect(jsonPath("$.details[0]").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
    }

    @Test
    void operatorAndViewerCanListButNotWrite() throws Exception {
        Login admin = login("admin");
        String id = createLocal(admin, "Local");
        for (String name : List.of("operator", "viewer")) {
            Login l = login(name);
            mvc.perform(get("/api/hosts").session(l.session())).andExpect(status().isOk());
            mvc.perform(l.write(post("/api/hosts")).contentType(APPLICATION_JSON).content(LOCAL.formatted("x")))
                    .andExpect(status().isForbidden());
            mvc.perform(l.write(post("/api/hosts/connection-test")).contentType(APPLICATION_JSON)
                            .content("{\"connectionType\":\"LOCAL\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(l.write(patch("/api/hosts/" + id)).contentType(APPLICATION_JSON).content("{\"name\":\"y\"}"))
                    .andExpect(status().isForbidden());
            mvc.perform(l.write(delete("/api/hosts/" + id))).andExpect(status().isForbidden());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isEqualTo(1);
    }

    @Test
    void unauthenticatedIsRejected() throws Exception {
        mvc.perform(get("/api/hosts")).andExpect(status().isUnauthorized());
    }

    @Test
    void writesRequireCsrfToken() throws Exception {
        Login admin = login("admin");
        mvc.perform(post("/api/hosts").session(admin.session()).contentType(APPLICATION_JSON)
                        .content(LOCAL.formatted("Local")))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/hosts/00000000-0000-0000-0000-000000000001").session(admin.session()))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM docker_hosts", Integer.class)).isZero();
    }

    @Test
    void storedTlsColumnsAreCiphertextAndNothingLeaksPem(CapturedOutput output) throws Exception {
        Login admin = login("admin");
        String[] pems = pems();
        // an unreachable endpoint is never saved, so insert through the service path is not possible via the API;
        // use the entity directly with the real cipher to check what ends up in the columns
        dev.bethge.shipir.common.security.SecretCipher cipher = cipher();
        jdbc.update("INSERT INTO docker_hosts (name, connection_type, host, port, tls_ca_encrypted, tls_cert_encrypted, "
                        + "tls_key_encrypted, owner_user_id) SELECT 'Remote', 'TCP', 'h', 2376, ?, ?, ?, id "
                        + "FROM users WHERE username = 'admin'",
                cipher.encrypt(pems[0]), cipher.encrypt(pems[1]), cipher.encrypt(pems[2]));

        Map<String, Object> row = jdbc.queryForMap("SELECT tls_ca_encrypted, tls_key_encrypted FROM docker_hosts");
        byte[] ca = (byte[]) row.get("tls_ca_encrypted");
        assertThat(new String(ca, StandardCharsets.ISO_8859_1)).doesNotContain("BEGIN CERTIFICATE");
        assertThat(cipher.decrypt(ca)).isEqualTo(pems[0]);

        String body = mvc.perform(get("/api/hosts").session(admin.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].tls").value(true))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("BEGIN").doesNotContain("tlsCaPem").doesNotContain("encrypted");
        assertThat(output.getAll()).doesNotContain("BEGIN CERTIFICATE").doesNotContain("PRIVATE KEY");
    }

    @org.springframework.beans.factory.annotation.Autowired
    private dev.bethge.shipir.common.security.SecretCipher secretCipher;

    private dev.bethge.shipir.common.security.SecretCipher cipher() {
        return secretCipher;
    }
}
