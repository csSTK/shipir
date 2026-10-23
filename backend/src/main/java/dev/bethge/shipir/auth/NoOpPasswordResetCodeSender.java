package dev.bethge.shipir.auth;

import org.springframework.stereotype.Component;

/** Default sender: drops the code (no mailer yet). Deliberately does not log it. */
@Component
class NoOpPasswordResetCodeSender implements PasswordResetCodeSender {

    @Override
    public void send(User user, String code) {
        // intentionally empty
    }
}
