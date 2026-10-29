package dev.bethge.shipir.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** NULL until the user has chosen their own password (first-start setup). */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    /** NULL while the user is enabled. Set when an ADMIN disables the account. */
    @Column(name = "disabled_at")
    private Instant disabledAt;

    /** AES-GCM ciphertext of the base32 TOTP secret; set at setup, before the user has confirmed a code. */
    @Column(name = "totp_secret_ciphertext")
    private byte[] totpSecretCiphertext;

    /** NULL until the first valid code confirmed the secret; only then is two-factor active. */
    @Column(name = "totp_enabled_at")
    private Instant totpEnabledAt;

    /** Time step of the last accepted code (replay protection). */
    @Column(name = "totp_last_used_step")
    private Long totpLastUsedStep;

    /** Issuer of the OIDC provider this user signs in through; NULL for local users (set together with the subject). */
    @Column(name = "external_issuer", length = 2048)
    private String externalIssuer;

    /** The provider's {@code sub} claim; unique per issuer. */
    @Column(name = "external_subject")
    private String externalSubject;

    /** UI language ({@code en} or {@code de}); NULL until the user chooses one. */
    @Column(length = 8)
    private String locale;

    protected User() {
    }

    /**
     * A user provisioned by single sign-on. The password hash is the hash of a random secret nobody ever sees,
     * and {@code passwordChangedAt} is set so the first-start password step does not apply.
     */
    public static User external(String username, String unusablePasswordHash, Role role, String issuer, String subject,
                                Instant now) {
        User user = new User(username, unusablePasswordHash, role);
        user.externalIssuer = issuer;
        user.externalSubject = subject;
        user.passwordChangedAt = now;
        return user;
    }

    public User(String username, String passwordHash, Role role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.createdAt = Instant.now();
    }

    public boolean isSetupRequired() {
        return passwordChangedAt == null;
    }

    public boolean isDisabled() {
        return disabledAt != null;
    }

    /** True for users signed in through single sign-on (an OIDC issuer is set); they have no local password. */
    public boolean isExternal() {
        return externalIssuer != null;
    }

    /** Stores the new hash and stamps {@code passwordChangedAt}, which ends the first-start setup. */
    public void changePasswordHash(String newPasswordHash, Instant changedAt) {
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = changedAt;
    }

    public void changeLocale(String newLocale) {
        this.locale = newLocale;
    }

    public void changeRole(Role newRole) {
        this.role = newRole;
    }

    public void disable(Instant at) {
        this.disabledAt = at;
    }

    public void enable() {
        this.disabledAt = null;
    }

    /** Stores a hash of an admin-issued one-time password; the user must choose their own on next login. */
    public void resetPasswordHash(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        this.passwordChangedAt = null;
    }

    public boolean isTwoFactorEnabled() {
        return totpEnabledAt != null;
    }

    /** Stores a not yet confirmed secret (replaces an earlier unconfirmed one). */
    public void beginTotpEnrolment(byte[] secretCiphertext) {
        this.totpSecretCiphertext = secretCiphertext;
        this.totpEnabledAt = null;
        this.totpLastUsedStep = null;
    }

    public void enableTotp(Instant at, long usedStep) {
        this.totpEnabledAt = at;
        this.totpLastUsedStep = usedStep;
    }

    public void recordTotpStep(long usedStep) {
        this.totpLastUsedStep = usedStep;
    }

    public void clearTotp() {
        this.totpSecretCiphertext = null;
        this.totpEnabledAt = null;
        this.totpLastUsedStep = null;
    }

    public byte[] getTotpSecretCiphertext() {
        return totpSecretCiphertext;
    }

    public Long getTotpLastUsedStep() {
        return totpLastUsedStep;
    }

    public String getExternalIssuer() {
        return externalIssuer;
    }

    public String getExternalSubject() {
        return externalSubject;
    }

    public UUID getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public Role getRole() {
        return role;
    }

    public String getLocale() {
        return locale;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPasswordChangedAt() {
        return passwordChangedAt;
    }

    public Instant getDisabledAt() {
        return disabledAt;
    }
}
