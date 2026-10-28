package dev.bethge.shipir.host;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A new controller in the host package maps HostException without being added to any list. */
class HostExceptionHandlerScopeTest {

    /** @TestComponent keeps the SpringBootTests' component scan from registering this route. */
    @TestComponent
    @RestController
    static class UnlistedHostController {
        @GetMapping("/unlisted")
        String boom() {
            throw new HostException(HttpStatus.CONFLICT, "SOME_CODE", "boom");
        }
    }

    @Test
    void controllerNotInAnyListStillMapsHostException() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new UnlistedHostController())
                .setControllerAdvice(new HostExceptionHandler()).build();
        mvc.perform(get("/unlisted"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOME_CODE"));
    }
}
