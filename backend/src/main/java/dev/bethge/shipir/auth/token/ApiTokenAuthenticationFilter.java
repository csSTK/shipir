package dev.bethge.shipir.auth.token;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * {@code Authorization: Bearer shp_...} authentication. A request with a Bearer header is authenticated by its token
 * alone (a session cookie sent along is ignored) or answered with the normal JSON 401; other schemes and requests
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

    public ApiTokenAuthenticationFilter(ApiTokenAuthenticator authenticator, AuthenticationEntryPoint entryPoint) {
        this.authenticator = authenticator;
        this.entryPoint = entryPoint;
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
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API token"));
            return;
        }
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication.get());
        SecurityContextHolder.setContext(context);
        request.setAttribute(AUTHENTICATED_ATTRIBUTE, Boolean.TRUE);
        chain.doFilter(request, response);
    }

    /** "Bearer" alone or followed by a space, in any case. */
    private static boolean isBearer(String header) {
        return header.regionMatches(true, 0, BEARER, 0, BEARER.length())
                && (header.length() == BEARER.length() || header.charAt(BEARER.length()) == ' ');
    }
}
