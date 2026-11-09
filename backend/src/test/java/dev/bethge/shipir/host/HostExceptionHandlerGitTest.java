package dev.bethge.shipir.host;

import dev.bethge.shipir.git.GitOperationException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A GitOperationException escaping a host/ caller keeps its status and code instead of falling through to a generic 500.
 * Covers the case ImageBuildService used to convert inline (shipr-ycvc.83).
 */
class HostExceptionHandlerGitTest {

    /** @TestComponent keeps the SpringBootTests' component scan from registering this route. */
    @TestComponent
    @RestController
    static class GitFailingHostController {
        @GetMapping("/git-failure")
        String boom(@RequestParam String kind) {
            if ("ref".equals(kind)) {
                throw new GitOperationException(HttpStatus.BAD_REQUEST, "INVALID_GIT_REF", "Invalid Git branch, tag or commit");
            }
            if ("timeout".equals(kind)) {
                throw new GitOperationException(HttpStatus.GATEWAY_TIMEOUT, "GIT_CLONE_TIMEOUT", "The repository clone timed out");
            }
            throw new IllegalStateException("not a git failure");
        }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new GitFailingHostController())
            .setControllerAdvice(new HostExceptionHandler()).build();

    @Test
    void invalidGitRefStays400WithItsCode() throws Exception {
        mvc.perform(get("/git-failure").param("kind", "ref"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_GIT_REF"))
                .andExpect(jsonPath("$.message").value("Invalid Git branch, tag or commit"));
    }

    @Test
    void cloneTimeoutKeeps504WithItsCode() throws Exception {
        mvc.perform(get("/git-failure").param("kind", "timeout"))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("GIT_CLONE_TIMEOUT"))
                .andExpect(header().doesNotExist("Retry-After"));
    }
}
