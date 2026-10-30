package dev.bethge.shipir.proxy;

import dev.bethge.shipir.common.exception.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProxyExceptionHandlerTest {

    private final ProxyExceptionHandler handler = new ProxyExceptionHandler();

    @Test
    void mapsStatusCodeMessageAndDetails() {
        ResponseEntity<ErrorResponse> r = handler.handle(
                new ProxyException(HttpStatus.CONFLICT, "PROXY_SETUP_BLOCKED", "Blocked", List.of("PORTS_IN_USE")));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(r.getBody()).isEqualTo(new ErrorResponse("PROXY_SETUP_BLOCKED", "Blocked", List.of("PORTS_IN_USE")));
    }

    @Test
    void exceptionsWithoutDetailsGetAnEmptyList() {
        ResponseEntity<ErrorResponse> r = handler.handle(new ProxyException(HttpStatus.BAD_GATEWAY, "PROXY_SYNC_FAILED", "x"));

        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(r.getBody().details()).isEmpty();
    }
}
