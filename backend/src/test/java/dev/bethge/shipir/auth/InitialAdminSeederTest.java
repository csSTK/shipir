package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "shipir.initial-admin.password=seed-secret-xyz")
@Import(PostgresTestConfiguration.class)
class InitialAdminSeederTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InitialAdminSeeder seeder;

    @Test
    void createsExactlyOneAdminWithBcryptHashAndPendingSetup() {
        assertThat(users.count()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT username, role, password_hash, password_changed_at FROM users");
        assertThat(row.get("username")).isEqualTo("admin");
        assertThat(row.get("role")).isEqualTo("ADMIN");
        assertThat(row.get("password_changed_at")).isNull();
        String hash = (String) row.get("password_hash");
        assertThat(hash).startsWith("{argon2}$argon2id$").isNotEqualTo("seed-secret-xyz");
        assertThat(passwordEncoder.matches("seed-secret-xyz", hash)).isTrue();

        User admin = users.findByUsername("admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(Role.ADMIN);
        assertThat(admin.isSetupRequired()).isTrue();
    }

    @Test
    void secondRunWithExistingUserChangesNothing() throws Exception {
        String hashBefore = jdbc.queryForObject("SELECT password_hash FROM users", String.class);

        seeder.run(null);

        assertThat(users.count()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT password_hash FROM users", String.class)).isEqualTo(hashBefore);
    }
}
