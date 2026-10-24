package dev.bethge.shipir.common.security;

import dev.bethge.shipir.common.exception.ErrorResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Optional;

/**
 * First-start gate: an authenticated user with a pending {@link SetupStep} may only reach
 * {@code GET /api/auth/me}, {@code POST /api/auth/logout} and {@code POST /api/auth/password}; during
 * {@link SetupStep#ENVIRONMENT} also {@code POST /api/hosts} and {@code POST /api/hosts/connection-test}, during
 * {@link SetupStep#TWO_FACTOR} also {@code POST /api/auth/2fa/setup} and {@code /confirm}.
 * Everything else under {@code /api/} gets 403 {@code SETUP_REQUIRED} (the message names the step). Unauthenticated requests pass
 * through so the normal 401 handling applies.
 */
public final class SetupGateFilter extends OncePerRequestFilter {

    private static final RequestMatcher API = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
    private static final RequestMatcher ALLOWED = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/api/auth/me"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/logout"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/password"));
    private static final RequestMatcher ENVIRONMENT_STEP_ALLOWED = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/hosts"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/hosts/connection-test"));

    private static final RequestMatcher TWO_FACTOR_STEP_ALLOWED = new OrRequestMatcher(
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/2fa/setup"),
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/auth/2fa/confirm"));

    private final SetupStatus setupStatus;
    private final JsonMapper mapper;

    public SetupGateFilter(SetupStatus setupStatus, JsonMapper mapper) {
        this.setupStatus = setupStatus;
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)
                && API.matches(request) && !ALLOWED.matches(request)) {
            Optional<SetupStep> step = setupStatus.currentStep(auth.getName());
            if (step.isPresent() && !(step.get() == SetupStep.ENVIRONMENT && ENVIRONMENT_STEP_ALLOWED.matches(request))
                    && !(step.get() == SetupStep.TWO_FACTOR && TWO_FACTOR_STEP_ALLOWED.matches(request))) {
                String message = switch (step.get()) {
                    case PASSWORD -> "Set your own password before using Shipir";
                    case TWO_FACTOR -> "Set up two-factor authentication before using Shipir";
                    case ENVIRONMENT -> "Add your first environment before using Shipir";
                };
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                response.setCharacterEncoding("UTF-8");
                mapper.writeValue(response.getWriter(), new ErrorResponse("SETUP_REQUIRED", message));
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
