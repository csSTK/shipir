package dev.bethge.shipir.auth.stepup;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

/** Tells the UI which kind of step-up dialog to show. */
@RestController
@RequestMapping("/api/auth/step-up")
class StepUpController {

    private final StepUpService service;

    StepUpController(StepUpService service) {
        this.service = service;
    }

    @GetMapping
    ResponseEntity<StepUpStatusResponse> status(Authentication authentication, HttpServletRequest request) {
        // time-dependent, per-user state: never cached
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.status(authentication, request));
    }
}
