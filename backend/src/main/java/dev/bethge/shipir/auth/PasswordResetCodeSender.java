package dev.bethge.shipir.auth;

/** Delivery port for reset codes (mail later). Implementations must never log the code. */
interface PasswordResetCodeSender {

    void send(User user, String code);
}
