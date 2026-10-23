package dev.bethge.shipir.auth;

/** Delivery port for reset codes (mail later). Implementations must never log the code. */
public interface PasswordResetCodeSender {

    void send(User user, String code);
}
