package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import dev.bethge.shipir.ShipirApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
class InitialAdminSeederStartupTest {

    private SpringApplication app() {
        SpringApplication app = new SpringApplication(ShipirApplication.class, PostgresTestConfiguration.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        return app;
    }

    @Test
    void failsStartupNamingTheVariableWhenPasswordIsBlank() {
        assertThatThrownBy(() -> app().run("--shipir.initial-admin.password="))
                .hasStackTraceContaining("SHIPIR_INITIAL_ADMIN_PASSWORD");
    }

    @Test
    void neverLogsThePlainPassword(CapturedOutput output) {
        try (ConfigurableApplicationContext ctx = app().run("--shipir.initial-admin.password=log-leak-check-4711")) {
            assertThat(ctx.getBean(UserRepository.class).count()).isEqualTo(1);
        }
        assertThat(output.getAll()).doesNotContain("log-leak-check-4711");
    }
}
