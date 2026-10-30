package dev.bethge.shipir.auth.totp;

import dev.bethge.shipir.common.exception.RequestThrottled;
import org.springframework.http.HttpStatus;

/** A second-factor attempt refused because the user is locked after too many wrong codes (429). */
class TwoFactorLockedException extends TwoFactorException implements RequestThrottled {

    TwoFactorLockedException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS", "Too many wrong codes, try again later");
    }
}
