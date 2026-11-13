package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.auth.User;
import dev.bethge.shipir.auth.UserRepository;
import dev.bethge.shipir.auth.stepup.StepUpService;
import dev.bethge.shipir.auth.stepup.StepUpSession;
import dev.bethge.shipir.auth.token.ApiTokenAuthentication;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.keygen.Base64StringKeyGenerator;
import org.springframework.security.crypto.keygen.StringKeyGenerator;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationExchange;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.core.endpoint.PkceParameterNames;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authorization code flow with PKCE (S256), state and nonce. {@link #begin} stores the pending request in the
 * session and returns the provider URL; {@link #complete} checks state, exchanges the code, verifies the ID token
 * (signature, issuer, audience, expiry, nonce), maps the identity to a user and signs that user in exactly like the
 * password login (new session id and CSRF token). Failures are {@link OidcException}s with a stable code that is
 * never built from provider text.
 */
@Service
class OidcLoginService {

    record LoginResult(UUID userId, String username, boolean provisioned) {
    }

    private static final Logger log = LoggerFactory.getLogger(OidcLoginService.class);
    private static final String PENDING_ATTRIBUTE = OidcLoginService.class.getName() + ".PENDING";
    private static final String DEFAULT_GROUPS_CLAIM = "groups";
    private static final String REAUTH_ATTRIBUTE = "shipir.oidc.reauth";
    private static final String RETURN_ATTRIBUTE = "shipir.oidc.return";
    /**
     * A same-origin path only, so the return target cannot become an open redirect: it must start with a single "/"
     * (no "//", scheme or host), contains no backslash, control character or whitespace, and may carry one optional
     * query ("?") and one optional fragment ("#") from a small safe character set, at most 2048 characters in all. Both only follow the path, so they
     * cannot move the target to another origin.
     */
    private static final Pattern LOCAL_PATH = Pattern.compile(
            "/(?!/)[A-Za-z0-9/_.~%-]*+(\\?[A-Za-z0-9/_.~%=&+-]*+)?+(#[A-Za-z0-9/_.~%=&+-]*+)?+");
    /** A "%" that does not start a two-digit hex escape; checked in a separate linear pass (no recursive regex). */
    private static final Pattern BAD_PERCENT = Pattern.compile("%(?![0-9A-Fa-f]{2})");
    /** Longer return paths are dropped (login lands on "/"); keeps matching cheap and the Location header small. */
    private static final int MAX_RETURN_PATH_LENGTH = 2048;

    private static boolean isLocalPath(String path) {
        return path != null && path.length() <= MAX_RETURN_PATH_LENGTH
                && LOCAL_PATH.matcher(path).matches() && !BAD_PERCENT.matcher(path).find();
    }

    private record RegistrationKey(OidcConfig config, OidcDiscoveryClient.Metadata metadata, String redirectUri) {
    }

    private record CachedRegistration(RegistrationKey key, ClientRegistration registration) {
    }

    private record DecoderKey(String jwksUri, String issuer, String clientId) {
    }

    private record CachedDecoder(DecoderKey key, JwtDecoder decoder) {
    }

    private final OidcSettingsService settings;
    private final OidcDiscoveryClient discovery;
    private final OidcDiscoveryCache discoveryCache;
    private final OidcUserResolver resolver;
    private final UserDetailsService userDetailsService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final OidcProperties properties;
    private final UserRepository users;
    private final StepUpService stepUp;
    /** Stateless and thread-safe, so built once instead of per callback. */
    private final RestClientAuthorizationCodeTokenResponseClient tokenClient;
    /** The last registration and ID token decoder, reused while config, discovery and redirect URI stay equal. */
    private volatile CachedRegistration cachedRegistration;
    private volatile CachedDecoder cachedDecoder;
    private final StringKeyGenerator randomToken = new Base64StringKeyGenerator(
            Base64.getUrlEncoder().withoutPadding(), 32);

    OidcLoginService(OidcSettingsService settings, OidcDiscoveryClient discovery, OidcDiscoveryCache discoveryCache,
                     OidcUserResolver resolver,
                     UserDetailsService userDetailsService, SessionAuthenticationStrategy sessionAuthenticationStrategy,
                     SecurityContextRepository securityContextRepository, OidcProperties properties,
                     UserRepository users, StepUpService stepUp) {
        this.users = users;
        this.stepUp = stepUp;
        this.settings = settings;
        this.discovery = discovery;
        this.discoveryCache = discoveryCache;
        this.resolver = resolver;
        this.userDetailsService = userDetailsService;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.properties = properties;
        this.tokenClient = tokenClient(discovery);
    }

    /** The provider URL to send the browser to; remembers state, nonce and PKCE verifier in the session. */
    String begin(HttpServletRequest request) {
        return begin(request, false, null);
    }

    /**
     * Like {@link #begin} for a user who is already signed in and must prove again, now, that they are who the session
     * says (step-up): asks the provider to log in again ({@code prompt=login}, {@code max_age}). {@code returnPath} is
     * where the callback sends the browser afterwards; anything but a same-origin path is dropped.
     */
    String beginReauth(HttpServletRequest request, String returnPath) {
        return begin(request, true, isLocalPath(returnPath) ? returnPath : null);
    }

    private String begin(HttpServletRequest request, boolean reauth, String returnPath) {
        OidcConfig config = activeConfig();
        OidcDiscoveryClient.Metadata metadata = discoveryCache.get(config.issuer());
        String redirectUri = redirectUri(request);
        String verifier = randomToken.generateKey();
        String nonce = randomToken.generateKey();
        OAuth2AuthorizationRequest authorizationRequest = OAuth2AuthorizationRequest.authorizationCode()
                .authorizationUri(metadata.authorizationEndpoint())
                .clientId(config.clientId())
                .redirectUri(redirectUri)
                .scopes(Set.of(config.scopes().split("\\s+")))
                .state(randomToken.generateKey())
                .additionalParameters(params -> {
                    params.put(OidcParameterNames.NONCE, nonce);
                    params.put(PkceParameterNames.CODE_CHALLENGE, s256(verifier));
                    params.put(PkceParameterNames.CODE_CHALLENGE_METHOD, "S256");
                    if (reauth) {
                        params.put("prompt", "login");
                        params.put("max_age", Long.toString(stepUp.ssoMaxAge().toSeconds()));
                    }
                })
                .attributes(attrs -> {
                    attrs.put(PkceParameterNames.CODE_VERIFIER, verifier);
                    attrs.put(OidcParameterNames.NONCE, nonce);
                    if (reauth) {
                        attrs.put(REAUTH_ATTRIBUTE, Boolean.TRUE);
                        attrs.put(RETURN_ATTRIBUTE, returnPath == null ? "/" : returnPath);
                    }
                })
                .build();
        request.getSession(true).setAttribute(PENDING_ATTRIBUTE, authorizationRequest);
        return authorizationRequest.getAuthorizationRequestUri();
    }

    @Auditable(action = "OIDC_LOGIN", targetType = "USER", targetId = "#result?.userId()",
            details = "{'provisioned': #result.provisioned()}")
    LoginResult complete(HttpServletRequest request, HttpServletResponse response, String code, String state,
                         String providerError) {
        Verified verified = verifyCallback(takePending(request), code, state, providerError);
        Jwt idToken = verified.idToken();
        OidcConfig config = verified.config();
        String subject = idToken.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw failure("ID_TOKEN_INVALID", "The ID token has no usable subject");
        }
        String groupsClaim = config.groupsClaim() == null ? DEFAULT_GROUPS_CLAIM : config.groupsClaim();
        OidcUserResolver.ResolvedUser user = resolver.resolve(config, subject, displayName(idToken, subject),
                groups(idToken.getClaims().get(groupsClaim)));
        signIn(user.username(), request, response);
        // The provider says when the user authenticated; kept so a step-up right after sign-in needs no second prompt.
        StepUpSession.clear(request.getSession(true));
        authTime(idToken).ifPresent(t -> StepUpSession.recordAuthTime(request.getSession(true), t));
        return new LoginResult(user.id(), user.username(), user.provisioned());
    }

    /** Where to send the browser after the callback if the pending request is a step-up re-authentication. */
    Optional<String> pendingReauthReturn(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        Object pending = session == null ? null : session.getAttribute(PENDING_ATTRIBUTE);
        if (pending instanceof OAuth2AuthorizationRequest r && Boolean.TRUE.equals(r.getAttribute(REAUTH_ATTRIBUTE))) {
            return Optional.of(r.getAttribute(RETURN_ATTRIBUTE));
        }
        return Optional.empty();
    }

    /**
     * Callback of a re-authentication: the verified ID token must be for the signed-in user (issuer and subject of
     * their link) and carry an {@code auth_time} within the step-up window. Nobody is signed in or out by this; on
     * success the session remembers the {@code auth_time}, see {@link StepUpSession}.
     */
    void completeReauth(HttpServletRequest request, String code, String state, String providerError) {
        Verified verified = verifyCallback(takePending(request), code, state, providerError);
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated() || current instanceof AnonymousAuthenticationToken
                || current instanceof ApiTokenAuthentication) {
            throw failure("NOT_SIGNED_IN", "Re-authentication needs a signed-in session");
        }
        User user = users.findByUsername(current.getName()).orElseThrow(
                () -> failure("NOT_SIGNED_IN", "Re-authentication needs a signed-in session"));
        if (!user.isExternal() || !verified.config().issuer().equals(user.getExternalIssuer())
                || !constantTimeEquals(user.getExternalSubject(), verified.idToken().getSubject())) {
            throw failure("IDENTITY_MISMATCH", "The provider identity is not the one of the signed-in user");
        }
        Instant authTime = authTime(verified.idToken())
                .filter(stepUp::isFresh)
                .orElseThrow(() -> failure("STEP_UP_NOT_FRESH", "The provider did not report a recent authentication"));
        StepUpSession.recordAuthTime(request.getSession(true), authTime);
    }

    private record Verified(Jwt idToken, OidcConfig config) {
    }

    /** State, provider error, code exchange, ID token verification and nonce: shared by sign-in and re-authentication. */
    private Verified verifyCallback(OAuth2AuthorizationRequest pending, String code, String state, String providerError) {
        if (pending == null || state == null || !constantTimeEquals(pending.getState(), state)) {
            throw failure("INVALID_STATE", "State does not match the sign-in started in this session");
        }
        if (providerError != null) {
            throw failure("OIDC_PROVIDER_ERROR", "The provider refused the sign-in");
        }
        if (code == null || code.isBlank()) {
            throw failure("OIDC_PROVIDER_ERROR", "No authorization code returned");
        }
        OidcConfig config = activeConfig();
        OidcDiscoveryClient.Metadata metadata = discoveryCache.get(config.issuer());
        ClientRegistration registration = registration(config, metadata, pending.getRedirectUri());

        Jwt idToken = exchangeAndVerify(registration, pending, code, state, config);
        String nonce = pending.getAttribute(OidcParameterNames.NONCE);
        if (nonce == null || !constantTimeEquals(nonce, idToken.getClaimAsString(OidcParameterNames.NONCE))) {
            throw failure("INVALID_NONCE", "The ID token nonce does not match");
        }
        return new Verified(idToken, config);
    }

    /** The {@code auth_time} claim (seconds since the epoch), if the provider sent a usable one. */
    private static Optional<Instant> authTime(Jwt idToken) {
        return switch (idToken.getClaim("auth_time")) {
            case Number n -> Optional.of(Instant.ofEpochSecond(n.longValue()));
            case Instant i -> Optional.of(i);
            case null, default -> Optional.empty();
        };
    }

    private Jwt exchangeAndVerify(ClientRegistration registration, OAuth2AuthorizationRequest pending, String code,
                                  String state, OidcConfig config) {
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success(code)
                .redirectUri(pending.getRedirectUri()).state(state).build();
        OAuth2AccessTokenResponse tokens;
        try {
            tokens = tokenClient.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
                    new OAuth2AuthorizationExchange(pending, authorizationResponse)));
        } catch (OAuth2AuthorizationException | RestClientException | IllegalArgumentException e) {
            throw failure("TOKEN_EXCHANGE_FAILED", "The authorization code could not be exchanged", e);
        }
        Object rawIdToken = tokens.getAdditionalParameters().get(OidcParameterNames.ID_TOKEN);
        if (!(rawIdToken instanceof String idTokenValue) || idTokenValue.isBlank()) {
            throw failure("ID_TOKEN_INVALID", "The provider returned no ID token");
        }
        try {
            requireTimestamps(idTokenValue);
            return decoder(registration, config).decode(idTokenValue);
        } catch (JwtException e) {
            throw failure("ID_TOKEN_INVALID", "The ID token could not be verified", e);
        }
    }

    private static RestClientAuthorizationCodeTokenResponseClient tokenClient(OidcDiscoveryClient discovery) {
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder().requestFactory(discovery.requestFactory())
                .configureMessageConverters(c -> c.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        return client;
    }

    private ClientRegistration registration(OidcConfig config, OidcDiscoveryClient.Metadata metadata,
                                            String redirectUri) {
        RegistrationKey key = new RegistrationKey(config, metadata, redirectUri);
        CachedRegistration cached = cachedRegistration;
        if (cached != null && cached.key().equals(key)) {
            return cached.registration();
        }
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .clientId(config.clientId()).clientSecret(config.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope(config.scopes().split("\\s+"))
                .authorizationUri(metadata.authorizationEndpoint()).tokenUri(metadata.tokenEndpoint())
                .jwkSetUri(metadata.jwksUri()).issuerUri(config.issuer()).userNameAttributeName("sub").build();
        cachedRegistration = new CachedRegistration(key, registration);
        return registration;
    }

    /**
     * Spring's claim conversion can fill in a missing {@code iat} and its timestamp validator skips a missing
     * {@code exp}, so the raw token must carry both.
     */
    private static void requireTimestamps(String idToken) {
        try {
            var claims = com.nimbusds.jwt.SignedJWT.parse(idToken).getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getIssueTime() == null) {
                throw new JwtException("ID token lacks exp or iat");
            }
        } catch (java.text.ParseException e) {
            throw new JwtException("ID token is not a signed JWT", e);
        }
    }

    private JwtDecoder decoder(ClientRegistration registration, OidcConfig config) {
        DecoderKey key = new DecoderKey(registration.getProviderDetails().getJwkSetUri(), config.issuer(),
                config.clientId());
        CachedDecoder cached = cachedDecoder;
        if (cached != null && cached.key().equals(key)) {
            return cached.decoder();
        }
        JwtDecoder built = buildDecoder(registration, config);
        cachedDecoder = new CachedDecoder(key, built);
        return built;
    }

    private JwtDecoder buildDecoder(ClientRegistration registration, OidcConfig config) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(registration.getProviderDetails().getJwkSetUri())
                .jwsAlgorithms(algs -> {
                    algs.add(SignatureAlgorithm.RS256);
                    algs.add(SignatureAlgorithm.ES256);
                    algs.add(SignatureAlgorithm.PS256);
                })
                .restOperations(new RestTemplate(discovery.requestFactory()))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(config.issuer()),
                // the default timestamp validator skips a missing exp, so presence is required explicitly
                new JwtClaimValidator<Object>("exp", exp -> exp != null),
                new JwtClaimValidator<Object>("iat", iat -> iat != null),
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(config.clientId())),
                // OIDC Core 3.1.3.7: with several audiences the authorized party must be this client
                jwt -> {
                    List<String> aud = jwt.getAudience();
                    boolean ok = aud != null && (aud.size() <= 1 || config.clientId().equals(jwt.getClaimAsString("azp")));
                    return ok ? OAuth2TokenValidatorResult.success()
                            : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "azp mismatch", null));
                }));
        return decoder;
    }

    private void signIn(String username, HttpServletRequest request, HttpServletResponse response) {
        UserDetails details = userDetailsService.loadUserByUsername(username);
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(details, null,
                details.getAuthorities());
        // Same as the password login: rotate the session id and CSRF token before the context is stored.
        sessionAuthenticationStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, request, response);
    }

    private OidcConfig activeConfig() {
        try {
            return settings.activeConfig().orElseThrow(
                    () -> new OidcException("OIDC_NOT_CONFIGURED", "Single sign-on is not configured"));
        } catch (EncryptionKeyMissingException e) {
            throw new OidcException("ENCRYPTION_KEY_MISSING", "The client secret cannot be decrypted");
        }
    }

    private OAuth2AuthorizationRequest takePending(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object pending = session.getAttribute(PENDING_ATTRIBUTE);
        session.removeAttribute(PENDING_ATTRIBUTE);
        return pending instanceof OAuth2AuthorizationRequest r ? r : null;
    }

    private String redirectUri(HttpServletRequest request) {
        return OidcRedirect.uri(properties, request);
    }

    private static String displayName(Jwt token, String subject) {
        for (String claim : new String[]{"preferred_username", "email"}) {
            String v = token.getClaimAsString(claim);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return subject;
    }

    private static List<String> groups(Object claim) {
        if (claim instanceof String s) {
            return List.of(s);
        }
        if (claim instanceof Collection<?> c) {
            return c.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }

    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        return a != null && b != null
                && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static OidcException failure(String code, String message) {
        return new OidcException(code, message);
    }

    /** Logs the exception type only: provider messages may echo tokens or secrets. */
    private static OidcException failure(String code, String message, Exception cause) {
        log.info("OIDC sign-in failed ({}): {}", code, cause.getClass().getSimpleName());
        return new OidcException(code, message);
    }
}
