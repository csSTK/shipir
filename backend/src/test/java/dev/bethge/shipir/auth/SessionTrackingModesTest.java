package dev.bethge.shipir.auth;

import dev.bethge.shipir.PostgresTestConfiguration;
import jakarta.servlet.ServletContext;
import jakarta.servlet.SessionTrackingMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session ids must travel in the cookie only. A ";jsessionid" in a URL leaks through logs and Referer headers and
 * bypasses the cookie flags (HttpOnly, SameSite, Secure). A real server is started so the container applies the
 * configured tracking modes to its servlet context.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(PostgresTestConfiguration.class)
class SessionTrackingModesTest {

    @Autowired
    private ServletContext servletContext;

    @Test
    void sessionIdTravelsOnlyInTheCookie() {
        assertThat(servletContext.getEffectiveSessionTrackingModes())
                .containsExactly(SessionTrackingMode.COOKIE);
    }
}
