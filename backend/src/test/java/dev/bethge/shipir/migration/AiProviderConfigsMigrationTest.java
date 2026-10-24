package dev.bethge.shipir.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V20 moves the AI endpoint/model/key into per-provider rows. Uses its own throwaway database because it must stop
 * at V19, customise the existing ai_settings row and only then apply V20.
 */
class AiProviderConfigsMigrationTest {

    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    static JdbcTemplate jdbc;
    static UUID user;

    @BeforeAll
    static void migrateToV19CustomiseSettingsThenApplyV20() {
        postgres.start();
        var dataSource = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).target("19").load().migrate();

        user = jdbc.queryForObject(
                "INSERT INTO users (username, password_hash, role) VALUES ('alice', 'x', 'ADMIN') RETURNING id",
                UUID.class);
        jdbc.update("""
                UPDATE ai_settings SET base_url = 'https://llm.example.com/v1', model = 'my-model',
                    api_key_ciphertext = ?, api_key_hint = '...abcd', enabled = TRUE, updated_by = ?
                WHERE id = 1
                """, new byte[]{1, 2, 3, 4}, user);

        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @Test
    void existingSettingsMoveToOpenAiCompatibleConfig() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM ai_provider_configs WHERE provider = 'OPENAI_COMPATIBLE'");
        assertThat(row).containsEntry("base_url", "https://llm.example.com/v1")
                .containsEntry("model", "my-model")
                .containsEntry("api_key_hint", "...abcd")
                .containsEntry("updated_by", user);
        assertThat((byte[]) row.get("api_key_ciphertext")).containsExactly(1, 2, 3, 4);
    }

    @Test
    void geminiIsSeededWithoutKeyAndSettingsKeepSwitchAndDefaultProvider() {
        Map<String, Object> gemini = jdbc.queryForMap("SELECT * FROM ai_provider_configs WHERE provider = 'GEMINI'");
        assertThat(gemini).containsEntry("base_url", "https://generativelanguage.googleapis.com/v1beta")
                .containsEntry("model", "gemini-flash-latest")
                .containsEntry("api_key_ciphertext", null)
                .containsEntry("api_key_hint", null);
        Map<String, Object> settings = jdbc.queryForMap("SELECT enabled, active_provider, updated_by FROM ai_settings");
        assertThat(settings).containsEntry("enabled", true)
                .containsEntry("active_provider", "OPENAI_COMPATIBLE")
                .containsEntry("updated_by", user);
    }
}
