package dev.bethge.shipir.auth.oidc;

import dev.bethge.shipir.auth.ClientAddressResolver;
import dev.bethge.shipir.auth.LoginThrottle;
import dev.bethge.shipir.auth.ThrottleLockoutAuditor;
import dev.bethge.shipir.auth.oidc.dto.OidcPublicConfigResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * Public single sign-on endpoints (GET, so no CSRF token; permitted in {@code SecurityConfig}). These are browser
 * navigations, so every failure is a redirect to {@code /?oidc_error=<CODE>}, never a response body.
 */
@RestController
@RequestMapping("/api/auth/oidc")
class OidcController {

    private static final Logger log = LoggerFactory.getLogger(OidcController.class);

    private final OidcLoginService login;
    private final OidcSettingsService settings;

    private final LoginThrottle throttle;
    private final ClientAddressResolver clientAddress;
    private final ThrottleLockoutAuditor lockoutAuditor;

    OidcController(OidcLoginService login, OidcSettingsService settings, LoginThrottle throttle,
                   ClientAddressResolver clientAddress, ThrottleLockoutAuditor lockoutAuditor) {
        this.login = login;
        this.settings = settings;
        this.throttle = throttle;
        this.clientAddress = clientAddress;
        this.lockoutAuditor = lockoutAuditor;
    }

    @GetMapping("/config")
    OidcPublicConfigResponse config() {
        return settings.publicConfig();
    }

    @GetMapping("/authorize")
    ResponseEntity<Void> authorize(HttpServletRequest request) {
        // Public and creates a session per call: limited per client address like the other unauthenticated endpoints.
        String address = clientAddress.resolve(request);
        LoginThrottle.Admission admission = throttle.admitAddress(LoginThrottle.Scope.OIDC_AUTHORIZE, address);
        if (admission.lockoutStarted()) {
            lockoutAuditor.lockoutStarted(LoginThrottle.Scope.OIDC_AUTHORIZE, address, address);
        }
        if (!admission.admitted()) {
            return redirect(errorLocation("TOO_MANY_ATTEMPTS"));
        }
        try {
            return redirect(login.begin(request));
        } catch (OidcException e) {
            return redirect(errorLocation(e.code()));
        }
    }

    @GetMapping("/callback")
    ResponseEntity<Void> callback(@RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  @RequestParam(required = false) String error,
                                  HttpServletRequest request, HttpServletResponse response) {
        try {
            login.complete(request, response, code, state, error);
            return redirect("/");
        } catch (OidcException e) {
            log.info("OIDC sign-in refused: {}", e.code());
            return redirect(errorLocation(e.code()));
        }
    }

    private static String errorLocation(String code) {
        return "/?oidc_error=" + code;
    }

    private static ResponseEntity<Void> redirect(String location) {
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(location).toString())
                .build();
    }
}
