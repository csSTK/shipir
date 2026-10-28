package dev.bethge.shipir.auth.token;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A token revoked or expired while a stream is running is rejected on the async re-dispatch. The answer must never
 * be written into a response that has already been sent, and must never let the request continue unauthenticated.
 */
class ApiTokenAuthenticationFilterAsyncTest {

    private static final String TOKEN = "shp_" + "A".repeat(TokenCodec.RANDOM_PART_LENGTH);

    private final ApiTokenAuthenticator authenticator = mock(ApiTokenAuthenticator.class);
    private final AuthenticationEntryPoint entryPoint = mock(AuthenticationEntryPoint.class);
    private final BearerAttemptGuard guard = mock(BearerAttemptGuard.class);
    private final FilterChain chain = mock(FilterChain.class);
    private final ApiTokenAuthenticationFilter filter =
            new ApiTokenAuthenticationFilter(authenticator, entryPoint, guard, JsonMapper.builder().build());

    private MockHttpServletRequest request;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        request = new MockHttpServletRequest("GET", "/api/stream");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        request.setDispatcherType(DispatcherType.ASYNC);
        response = new MockHttpServletResponse();
        when(authenticator.authenticate(TOKEN)).thenReturn(Optional.empty());
        when(guard.lockedFor(any())).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("u", "p", "ROLE_OPERATOR"));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void revokedTokenOnAsyncDispatchOfCommittedResponseWritesNothingAndDoesNotContinue() throws Exception {
        response.getWriter().write("data: first\n\n");
        response.flushBuffer();
        assertThat(response.isCommitted()).isTrue();

        filter.doFilter(request, response, chain);

        verify(entryPoint, never()).commence(any(), any(), any());
        verify(guard, never()).recordFailure(any());
        verify(chain, never()).doFilter(any(), any());
        assertThat(response.getContentAsString()).isEqualTo("data: first\n\n");
        assertThat(response.getStatus()).isEqualTo(200);
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        assertThat(current).as("no authentication is left for the rejected dispatch").isNull();
    }

    @Test
    void revokedTokenOnAsyncDispatchOfUncommittedResponseStillGets401() throws Exception {
        assertThat(response.isCommitted()).isFalse();

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any());
        verify(guard).recordFailure(any());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void revokedTokenOnInitialRequestStillGets401() throws Exception {
        request.setDispatcherType(DispatcherType.REQUEST);

        filter.doFilter(request, response, chain);

        verify(entryPoint).commence(any(), any(), any());
        verify(chain, never()).doFilter(any(), any());
    }
}
