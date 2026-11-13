package dev.bethge.shipir.auth.stepup;

import dev.bethge.shipir.common.exception.RequestThrottled;
import org.springframework.http.HttpStatus;

import java.time.Duration;

/** A step-up refused because the throttle is locked (429, code {@code STEP_UP_THROTTLED} so clients can tell it from other rate limits); the audit aspect writes no row for it. */
class StepUpThrottledException extends StepUpException implements RequestThrottled {

    StepUpThrottledException(Duration retryAfter) {
        super(HttpStatus.TOO_MANY_REQUESTS, "STEP_UP_THROTTLED", "Too many attempts, try again later", retryAfter);
    }
}
