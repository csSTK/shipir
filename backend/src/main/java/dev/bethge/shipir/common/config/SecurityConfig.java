package dev.bethge.shipir.common.config;

import dev.bethge.shipir.auth.token.ApiTokenAuthentication;
import dev.bethge.shipir.auth.token.ApiTokenAuthenticationFilter;
import dev.bethge.shipir.auth.token.ApiTokenAuthenticator;
import dev.bethge.shipir.common.exception.ErrorResponse;
import dev.bethge.shipir.common.security.SetupGateFilter;
import dev.bethge.shipir.common.security.SetupStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.DeferredSecurityContext;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.authentication.session.RegisterSessionAuthenticationStrategy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpRequestResponseHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.List;

/**
 * Server-side session auth for the SPA: JSON login via {@code AuthController}, HttpOnly session cookie,
 * CSRF through the double-submit {@code XSRF-TOKEN} cookie, JSON 401/403 instead of redirects, security headers.
 */
@Configuration(proxyBeanMethods = false)
class SecurityConfig {

    /**
     * Same policy as frontend/nginx/default.conf.template (keep both in sync; the CSP e2e reads the nginx one).
     * Inline styles are needed by the UI libraries (style attributes, Monaco); scripts stay 'self' only.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
            + "connect-src 'self' ws: wss:; worker-src 'self' blob:; object-src 'none'; "
            + "base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    static final String PERMISSIONS_POLICY = "camera=(), microphone=(), geolocation=(), payment=(), usb=()";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            SecurityContextRepository securityContextRepository,
                                            CsrfTokenRepository csrfTokenRepository,
                                            AuthenticationEntryPoint entryPoint,
                                            AccessDeniedHandler accessDeniedHandler,
                                            SetupStatus setupStatus,
                                            SessionRegistry sessionRegistry,
                                            ApiTokenAuthenticator apiTokenAuthenticator,
                                            JsonMapper mapper,
                                            @Value("${shipir.security.https:false}") boolean https) throws Exception {
        http
                .headers(h -> {
                    h.contentSecurityPolicy(csp -> csp.policyDirectives(CONTENT_SECURITY_POLICY));
                    h.frameOptions(f -> f.deny());
                    h.referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER));
                    h.permissionsPolicyHeader(p -> p.policy(PERMISSIONS_POLICY));
                    if (https) {
                        // TLS ends at the reverse proxy, so the request itself never looks secure: always send it
                        h.httpStrictTransportSecurity(hsts -> hsts.requestMatcher(AnyRequestMatcher.INSTANCE)
                                .maxAgeInSeconds(31_536_000).includeSubDomains(true));
                    } else {
                        h.httpStrictTransportSecurity(hsts -> hsts.disable());
                    }
                })
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(c -> c.disable())
                .securityContext(c -> c.securityContextRepository(securityContextRepository))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        // Token requests carry no cookie-borne credential (CSRF cannot apply); the webhook trigger is
                        // public and authenticated by the secret in its path. Nothing else is exempt.
                        .ignoringRequestMatchers(
                                request -> request.getAttribute(ApiTokenAuthenticationFilter.AUTHENTICATED_ATTRIBUTE) != null,
                                PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/webhooks/deploy/*"))
                        // plain (non-BREACH) handler: the SPA echoes the cookie value back as header
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterBefore(new ApiTokenAuthenticationFilter(apiTokenAuthenticator, entryPoint), CsrfFilter.class)
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(new SetupGateFilter(setupStatus, mapper), AuthorizationFilter.class)
                .sessionManagement(sm -> sm.maximumSessions(-1).sessionRegistry(sessionRegistry)
                        .expiredSessionStrategy(event -> writeJson(mapper, event.getResponse(), HttpStatus.UNAUTHORIZED,
                                new ErrorResponse("UNAUTHENTICATED", "Authentication required"))))
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login", "/api/auth/logout",
                                "/api/auth/password-reset/request", "/api/auth/password-reset/confirm",
                                "/api/auth/2fa/verify").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/webhooks/deploy/*").permitAll()
                        // personal API tokens are managed with the session only: a token cannot mint or list tokens
                        .requestMatchers("/api/account/tokens", "/api/account/tokens/**").access((supplier, context) ->
                                new AuthorizationDecision(sessionAuthenticated(supplier.get())))
                        .requestMatchers(HttpMethod.GET, "/api/auth/oidc/config", "/api/auth/oidc/authorize",
                                "/api/auth/oidc/callback").permitAll()
                        // managed deployments: every role reads (env values only ADMIN/OPERATOR), ADMIN/OPERATOR write
                        .requestMatchers(HttpMethod.GET, "/api/hosts/*/managed-deployments/*/env").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/hosts/*/managed-deployments", "/api/hosts/*/managed-deployments/*",
                                "/api/hosts/*/managed-deployments/*/versions", "/api/hosts/*/managed-deployments/*/versions/*",
                                "/api/hosts/*/managed-deployments/*/files",
                                "/api/hosts/*/managed-deployments/*/files/backups").authenticated()
                        .requestMatchers("/api/hosts/*/managed-deployments", "/api/hosts/*/managed-deployments/**").hasAnyRole("ADMIN", "OPERATOR")
                        // environments: every role reads, only ADMIN writes (create, test, rename, delete)
                        .requestMatchers(HttpMethod.DELETE, "/api/hosts/*/deployments/*").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/deployments/*/adopt").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.DELETE, "/api/hosts/*/containers/*", "/api/hosts/*/images/*",
                                "/api/hosts/*/volumes/*", "/api/hosts/*/networks/*").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/containers/*/start", "/api/hosts/*/containers/*/stop",
                                "/api/hosts/*/containers/*/restart", "/api/hosts/*/containers/*/pause",
                                "/api/hosts/*/containers/*/unpause", "/api/hosts/*/containers/*/kill")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/containers/*/recreate", "/api/hosts/*/containers/*/duplicate")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.PATCH, "/api/hosts/*/containers/*/resources").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/containers", "/api/hosts/*/networks",
                                "/api/hosts/*/volumes").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/images/pull").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/containers/*/exec").hasRole("ADMIN")
                        .requestMatchers("/api/hosts/*/containers/*/files", "/api/hosts/*/containers/*/files/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/hosts/*/volumes/*/backup").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/volumes/*/restore").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/hosts/*/volume-backup-schedules", "/api/hosts/*/volume-backup-schedules/*")
                        .hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/hosts/*/prune/preview").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.POST, "/api/hosts/*/prune").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/hosts", "/api/hosts/**").authenticated()
                        .requestMatchers("/api/hosts", "/api/hosts/**").hasRole("ADMIN")
                        .requestMatchers("/api/compose/generate").hasAnyRole("ADMIN", "OPERATOR")
                        .requestMatchers("/api/audit-log", "/api/audit-log/**").hasRole("ADMIN")
                        .requestMatchers("/api/notifications/webhooks", "/api/notifications/webhooks/**").hasRole("ADMIN")
                        .requestMatchers("/api/users", "/api/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/git/**").hasRole("ADMIN")
                        .requestMatchers("/api/backup", "/api/backup/**").hasRole("ADMIN")
                        .requestMatchers("/api/registries", "/api/registries/**").hasRole("ADMIN")
                        .requestMatchers("/api/settings/retention").hasRole("ADMIN")
                        .requestMatchers("/api/settings/security").hasRole("ADMIN")
                        .requestMatchers("/api/settings/ai", "/api/settings/ai/**").hasRole("ADMIN")
                        .requestMatchers("/api/settings/oidc", "/api/settings/oidc/**").hasRole("ADMIN")
                        .requestMatchers("/api/ai/shared-sessions", "/api/ai/shared-sessions/**").hasRole("ADMIN")
                        .anyRequest().authenticated());
        return http.build();
    }

    private static boolean sessionAuthenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken)
                && !(authentication instanceof ApiTokenAuthentication);
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new SessionOrTokenSecurityContextRepository(new HttpSessionSecurityContextRepository());
    }

    /** Tracks sessions per user so an ADMIN action (disable, reset, delete) can end them. */
    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Lets the registry forget sessions that the container destroys (timeout, logout). */
    @Bean
    HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /** {@code Secure} on the XSRF cookie when {@code shipir.security.https=true} (the session cookie: application.yml). */
    @Bean
    CsrfTokenRepository csrfTokenRepository(@Value("${shipir.security.https:false}") boolean https) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie.secure(https));
        return repository;
    }

    /** Applied by the login endpoint: new session id and new CSRF token once the user is authenticated. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrfTokenRepository,
                                                                SessionRegistry sessionRegistry) {
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessionRegistry),
                new CsrfAuthenticationStrategy(csrfTokenRepository)));
    }

    @Bean
    AuthenticationEntryPoint jsonAuthenticationEntryPoint(JsonMapper mapper) {
        return (request, response, ex) -> writeJson(mapper, response, HttpStatus.UNAUTHORIZED,
                new ErrorResponse("UNAUTHENTICATED", "Authentication required"));
    }

    @Bean
    AccessDeniedHandler jsonAccessDeniedHandler(JsonMapper mapper) {
        return (request, response, ex) -> writeJson(mapper, response, HttpStatus.FORBIDDEN,
                new ErrorResponse("FORBIDDEN", "Access denied"));
    }

    private static void writeJson(JsonMapper mapper, HttpServletResponse response, HttpStatus status,
                                  ErrorResponse body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), body);
    }

    /**
     * The HTTP session repository, except for token-authenticated requests: those are never saved, and reported as
     * already having a context so {@code SessionManagementFilter} does not create and register a session for them.
     */
    private static final class SessionOrTokenSecurityContextRepository implements SecurityContextRepository {

        private final SecurityContextRepository session;

        SessionOrTokenSecurityContextRepository(SecurityContextRepository session) {
            this.session = session;
        }

        @Override
        @SuppressWarnings("deprecation")
        public SecurityContext loadContext(HttpRequestResponseHolder holder) {
            return session.loadContext(holder);
        }

        @Override
        public DeferredSecurityContext loadDeferredContext(HttpServletRequest request) {
            return session.loadDeferredContext(request);
        }

        @Override
        public void saveContext(SecurityContext context, HttpServletRequest request, HttpServletResponse response) {
            if (!tokenAuthenticated(request)) {
                session.saveContext(context, request, response);
            }
        }

        @Override
        public boolean containsContext(HttpServletRequest request) {
            return tokenAuthenticated(request) || session.containsContext(request);
        }

        private static boolean tokenAuthenticated(HttpServletRequest request) {
            return request.getAttribute(ApiTokenAuthenticationFilter.AUTHENTICATED_ATTRIBUTE) != null;
        }
    }

    /** Forces the deferred CSRF token to load so the XSRF-TOKEN cookie is set on every response, even a 401. */
    private static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain chain) throws ServletException, IOException {
            CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (token != null) {
                token.getToken();
            }
            chain.doFilter(request, response);
        }
    }
}
