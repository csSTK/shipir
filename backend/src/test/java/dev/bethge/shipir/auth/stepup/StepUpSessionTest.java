package dev.bethge.shipir.auth.stepup;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class StepUpSessionTest {

    @Test
    void clearToleratesAMissingSession() {
        assertThatCode(() -> StepUpSession.clear(null)).doesNotThrowAnyException();
    }

    @Test
    void clearForgetsTheRecordedAuthTime() {
        MockHttpSession session = new MockHttpSession();
        StepUpSession.recordAuthTime(session, Instant.now());
        StepUpSession.clear(session);
        assertThat(StepUpSession.authTime(session)).isEmpty();
    }
}
