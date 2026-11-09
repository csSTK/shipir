package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Other packages map HostException by status and details, so both accessors are part of its public API. */
class HostExceptionVisibilityTest {

    @Test
    void statusAndDetailsArePublic() throws Exception {
        assertThat(Modifier.isPublic(HostException.class.getMethod("status").getModifiers())).isTrue();
        assertThat(Modifier.isPublic(HostException.class.getMethod("details").getModifiers())).isTrue();
    }

    @Test
    void accessorsReturnTheConstructorValues() {
        HostException e = new HostException(org.springframework.http.HttpStatus.CONFLICT, "X", "msg", List.of("a"));

        assertThat(e.status().value()).isEqualTo(409);
        assertThat(e.details()).containsExactly("a");
        assertThat(e.code()).isEqualTo("X");
        assertThat(new HostException(org.springframework.http.HttpStatus.NOT_FOUND, "Y", "m").details()).isEmpty();
    }
}
