package dev.bethge.shipir.auth.stepup;

import dev.bethge.shipir.common.exception.RequestThrottled;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/** A step-up refused because the throttle is locked (429); the audit aspect writes no row for it. */
class StepUpThrottledException extends StepUpException implements RequestThrottled {

    StepUpThrottledException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many attempts, try again later", retryAfter);
    }
}
