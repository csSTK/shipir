package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.audit.Auditable;
import dev.bethge.shipir.common.security.EncryptionKeyMissingException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final OidcSettingsService settings;
    private final OidcDiscoveryClient discovery;
    private final OidcUserResolver resolver;
    private final UserDetailsService userDetailsService;
    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;
    private final SecurityContextRepository securityContextRepository;
    private final OidcProperties properties;
    private final StringKeyGenerator randomToken = new Base64StringKeyGenerator(
            Base64.getUrlEncoder().withoutPadding(), 32);

    OidcLoginService(OidcSettingsService settings, OidcDiscoveryClient discovery, OidcUserResolver resolver,
                     UserDetailsService userDetailsService, SessionAuthenticationStrategy sessionAuthenticationStrategy,
                     SecurityContextRepository securityContextRepository, OidcProperties properties) {
        this.settings = settings;
        this.discovery = discovery;
        this.resolver = resolver;
        this.userDetailsService = userDetailsService;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.securityContextRepository = securityContextRepository;
        this.properties = properties;
    }

    /** The provider URL to send the browser to; remembers state, nonce and PKCE verifier in the session. */
    String begin(HttpServletRequest request) {
        OidcConfig config = activeConfig();
        OidcDiscoveryClient.Metadata metadata = discovery.fetch(config.issuer());
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
                })
                .attributes(attrs -> {
                    attrs.put(PkceParameterNames.CODE_VERIFIER, verifier);
                    attrs.put(OidcParameterNames.NONCE, nonce);
                })
                .build();
        request.getSession(true).setAttribute(PENDING_ATTRIBUTE, authorizationRequest);
        return authorizationRequest.getAuthorizationRequestUri();
    }

    @Auditable(action = "OIDC_LOGIN", targetType = "USER", targetId = "#result?.userId()",
            details = "{'provisioned': #result.provisioned()}")
    LoginResult complete(HttpServletRequest request, HttpServletResponse response, String code, String state,
                         String providerError) {
        OAuth2AuthorizationRequest pending = takePending(request);
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
        OidcDiscoveryClient.Metadata metadata = discovery.fetch(config.issuer());
        ClientRegistration registration = ClientRegistration.withRegistrationId("oidc")
                .clientId(config.clientId()).clientSecret(config.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(pending.getRedirectUri())
                .scope(config.scopes().split("\\s+"))
                .authorizationUri(metadata.authorizationEndpoint()).tokenUri(metadata.tokenEndpoint())
                .jwkSetUri(metadata.jwksUri()).issuerUri(config.issuer()).userNameAttributeName("sub").build();

        Jwt idToken = exchangeAndVerify(registration, pending, code, state, config);
        String nonce = pending.getAttribute(OidcParameterNames.NONCE);
        if (nonce == null || !constantTimeEquals(nonce, idToken.getClaimAsString(OidcParameterNames.NONCE))) {
            throw failure("INVALID_NONCE", "The ID token nonce does not match");
        }
        String subject = idToken.getSubject();
        if (subject == null || subject.isBlank() || subject.length() > 255) {
            throw failure("ID_TOKEN_INVALID", "The ID token has no usable subject");
        }
        String groupsClaim = config.groupsClaim() == null ? DEFAULT_GROUPS_CLAIM : config.groupsClaim();
        OidcUserResolver.ResolvedUser user = resolver.resolve(config, subject, displayName(idToken, subject),
                groups(idToken.getClaims().get(groupsClaim)));
        signIn(user.username(), request, response);
        return new LoginResult(user.id(), user.username(), user.provisioned());
    }

    private Jwt exchangeAndVerify(ClientRegistration registration, OAuth2AuthorizationRequest pending, String code,
                                  String state, OidcConfig config) {
        OAuth2AuthorizationResponse authorizationResponse = OAuth2AuthorizationResponse.success(code)
                .redirectUri(pending.getRedirectUri()).state(state).build();
        RestClientAuthorizationCodeTokenResponseClient client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(RestClient.builder().requestFactory(discovery.requestFactory())
                .configureMessageConverters(c -> c.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter()))
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler()).build());
        OAuth2AccessTokenResponse tokens;
        try {
            tokens = client.getTokenResponse(new OAuth2AuthorizationCodeGrantRequest(registration,
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
