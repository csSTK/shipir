package dev.bethge.shipir.host;

import dev.bethge.shipir.SetupGateFixture;
import dev.bethge.shipir.docker.client.VolumeArchiveService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD of volume backup schedules: roles, host permission, validation, uniqueness, audit. */
class VolumeBackupScheduleApiTest extends HostApiTestBase {

    @MockitoBean
    VolumeArchiveService archives;

    private UUID hostId;
    private String url;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM audit_log");
        SetupGateFixture.resetWithAdminHost(jdbc, "local");
        hostId = jdbc.queryForObject("SELECT id FROM docker_hosts WHERE name = 'local'", UUID.class);
        url = "/api/hosts/" + hostId + "/volume-backup-schedules";
    }

    private static String body(String volume, int interval, int keep) {
        return "{\"volumeName\":\"%s\",\"intervalHours\":%d,\"keepCount\":%d}".formatted(volume, interval, keep);
    }

    private List<String> audit(String action) {
        return jdbc.queryForList("SELECT outcome || ':' || coalesce(target_id, '') FROM audit_log WHERE action = ?",
                String.class, action);
    }

    @Test
    void createListAndDeleteRoundTrip() throws Exception {
        Login l = login("operator");
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(l.write(post(url).contentType(APPLICATION_JSON)
                        .content(body("data", 24, 7))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.volumeName").value("data"))
                .andExpect(jsonPath("$.intervalHours").value(24))
                .andExpect(jsonPath("$.keepCount").value(7))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty())
                .andExpect(jsonPath("$.lastStatus").doesNotExist())
                .andReturn().getResponse().getContentAsString(), "$.id");

        mvc.perform(get(url).session(l.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(id));

        mvc.perform(l.write(delete(url + "/" + id))).andExpect(status().isNoContent());
        mvc.perform(get(url).session(l.session())).andExpect(jsonPath("$.length()").value(0));
        assertThat(audit("VOLUME_BACKUP_SCHEDULE_CREATE")).containsExactly("SUCCESS:data");
        assertThat(audit("VOLUME_BACKUP_SCHEDULE_DELETE")).hasSize(1);
    }

    @Test
    void viewerIsForbiddenEverywhere() throws Exception {
        Login l = login("viewer");
        mvc.perform(get(url).session(l.session())).andExpect(status().isForbidden());
        mvc.perform(l.write(post(url).contentType(APPLICATION_JSON).content(body("data", 24, 7))))
                .andExpect(status().isForbidden());
        mvc.perform(l.write(delete(url + "/" + UUID.randomUUID()))).andExpect(status().isForbidden());
    }

    @Test
    void operatorWithViewGrantOnlyIsForbidden() throws Exception {
        grantHost("operator", hostId, "VIEW");
        Login l = login("operator");
        mvc.perform(l.write(post(url).contentType(APPLICATION_JSON).content(body("data", 24, 7))))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM volume_backup_schedules", Integer.class)).isZero();
    }

    @Test
    void unknownHostIs404() throws Exception {
        mvc.perform(get("/api/hosts/" + UUID.randomUUID() + "/volume-backup-schedules")
                        .session(login("admin").session()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("HOST_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc", "a b", "a/b", "-x", ""})
    void invalidVolumeNamesAre400(String name) throws Exception {
        mvc.perform(login("admin").write(post(url).contentType(APPLICATION_JSON).content(body(name, 24, 7))))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM volume_backup_schedules", Integer.class)).isZero();
    }

    @Test
    void outOfRangeIntervalAndKeepCountAre400() throws Exception {
        Login l = login("admin");
        for (String b : new String[]{body("data", 0, 7), body("data", 8761, 7), body("data", 24, 0), body("data", 24, 101)}) {
            mvc.perform(l.write(post(url).contentType(APPLICATION_JSON).content(b))).andExpect(status().isBadRequest());
        }
    }

    @Test
    void secondScheduleForTheSameVolumeIs409() throws Exception {
        Login l = login("admin");
        mvc.perform(l.write(post(url).contentType(APPLICATION_JSON).content(body("data", 24, 7)))).andExpect(status().isCreated());
        mvc.perform(l.write(post(url).contentType(APPLICATION_JSON).content(body("data", 12, 3))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SCHEDULE_EXISTS"));
    }

    @Test
    void deleteOfForeignOrUnknownScheduleIs404() throws Exception {
        mvc.perform(login("admin").write(delete(url + "/" + UUID.randomUUID())))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
    }

    @Test
    void createRequiresCsrf() throws Exception {
        mvc.perform(post(url).session(login("admin").session()).contentType(APPLICATION_JSON).content(body("data", 24, 7)))
                .andExpect(status().isForbidden());
    }
}
