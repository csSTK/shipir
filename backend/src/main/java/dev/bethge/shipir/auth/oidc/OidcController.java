package dev.bethge.shipir.auth.oidc;

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

    OidcController(OidcLoginService login, OidcSettingsService settings) {
        this.login = login;
        this.settings = settings;
    }

    @GetMapping("/config")
    OidcPublicConfigResponse config() {
        return settings.publicConfig();
    }

    @GetMapping("/authorize")
    ResponseEntity<Void> authorize(HttpServletRequest request) {
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
