package dev.bethge.shipir.git;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Username and personal access token for a private repository. The token exists only as ciphertext. */
@Entity
@Table(name = "git_credentials")
public class GitCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "token_ciphertext", nullable = false)
    private byte[] tokenCiphertext;

    @Column(name = "token_hint", nullable = false, length = 32)
    private String tokenHint;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected GitCredential() {
    }

    GitCredential(String name, String username, byte[] tokenCiphertext, String tokenHint, UUID createdBy,
                  Instant now) {
        this.name = name;
        this.username = username;
        this.tokenCiphertext = tokenCiphertext;
        this.tokenHint = tokenHint;
        this.createdBy = createdBy;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void update(String name, String username, Instant now) {
        this.name = name;
        this.username = username;
        this.updatedAt = now;
    }

    void replaceToken(byte[] tokenCiphertext, String tokenHint) {
        this.tokenCiphertext = tokenCiphertext;
        this.tokenHint = tokenHint;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getUsername() { return username; }
    public String getTokenHint() { return tokenHint; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    byte[] getTokenCiphertext() { return tokenCiphertext; }

    @Override
    public String toString() {
        return "GitCredential[id=" + id + ", name=" + name + ", username=" + username + "]";
    }
}
