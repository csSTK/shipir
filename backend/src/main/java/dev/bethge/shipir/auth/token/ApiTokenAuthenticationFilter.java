package dev.bethge.shipir.auth.token;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import dev.bethge.shipir.common.exception.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

/**
 * {@code Authorization: Bearer shp_...} authentication. A request with a Bearer header is authenticated by its token
 * alone (a session cookie sent along is ignored) or answered with the normal JSON 401 (a rejected credential counts against the client address, and from a locked address is answered with 429; valid tokens are never refused; see {@link BearerAttemptGuard}); other schemes and requests
 * without the header pass through untouched. The context is not saved, so no session is created, and the request is
 * marked so CSRF protection is skipped: the header cannot be set by a cross-site form or a browser-attached cookie.
 * Deliberately not a bean: {@code SecurityConfig} registers it in the security chain only.
 */
public final class ApiTokenAuthenticationFilter extends OncePerRequestFilter {

    /** Request attribute set on token-authenticated requests, used to exempt them from CSRF. */
    public static final String AUTHENTICATED_ATTRIBUTE = ApiTokenAuthenticationFilter.class.getName() + ".AUTHENTICATED";

    private static final String BEARER = "bearer";

    private final ApiTokenAuthenticator authenticator;
    private final AuthenticationEntryPoint entryPoint;
    private final BearerAttemptGuard guard;
    private final JsonMapper mapper;

    public ApiTokenAuthenticationFilter(ApiTokenAuthenticator authenticator, AuthenticationEntryPoint entryPoint,
                                        BearerAttemptGuard guard, JsonMapper mapper) {
        this.authenticator = authenticator;
        this.entryPoint = entryPoint;
        this.guard = guard;
        this.mapper = mapper;
    }

    /** Streaming responses are written after an async dispatch, which must be authenticated again (the context is cleared). */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !isBearer(header)) {
            chain.doFilter(request, response);
            return;
        }
        String presented = header.length() > BEARER.length() ? header.substring(BEARER.length() + 1) : "";
        Optional<Authentication> authentication = authenticator.authenticate(presented);
        if (authentication.isEmpty()) {
            SecurityContextHolder.clearContext();
            if (request.getDispatcherType() == DispatcherType.ASYNC && response.isCommitted()) {
                // The token was revoked or expired while a stream was running. The response is already on the wire, so
                // there is nothing left to answer: do not write a 401 into it and do not count a failed attempt. The chain
                // is not continued and the context stays cleared, so the dispatch is never processed as authenticated.
                return;
            }
            // A valid token is never refused. A rejected credential from a locked address gets 429 and is not counted again.
            Optional<Duration> locked = guard.lockedFor(request);
            if (locked.isPresent()) {
                tooManyAttempts(response, locked.get());
                return;
            }
            guard.recordFailure(request);
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API token"));
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication.get());
        SecurityContextHolder.setContext(context);
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    private void tooManyAttempts(HttpServletResponse response, Duration retryAfter) throws IOException {
        long seconds = Math.max(1, (retryAfter.toMillis() + 999) / 1000);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(seconds));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), new ErrorResponse("TOO_MANY_ATTEMPTS", "Too many attempts, try again later"));
    }

    /** "Bearer" alone or followed by a space, in any case. */
    private static boolean isBearer(String header) {
        return header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                && (header.length() == BEARER.length() || header.charAt(BEARER.length()) == ' ');
    }
}
