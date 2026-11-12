package com.dnd.auth;

import java.util.UUID;

/**
 * A registered application account. This is intentionally separate from the
 * per-campaign {@code PlayerCharacter} model: one {@code User} (a real person)
 * logs in once and can then act as DM or Player across any number of
 * campaigns/characters.
 *
 * <p>Timestamps are stored as epoch-millisecond {@code Long}s rather than
 * {@code java.time.Instant} to avoid pulling in the
 * {@code jackson-datatype-jsr310} module just for this one model.</p>
 */
public class User {
    private String id = UUID.randomUUID().toString();
    private String username;
    private String email;
    private String providerUid;
    private String passwordHash;
    private String passwordSalt;
    private boolean emailVerified;
    private String verificationCode;
    private Long verificationExpiresAtMillis;
    private String resetCode;
    private Long resetExpiresAtMillis;
    private long createdAtMillis = System.currentTimeMillis();

    public User() {
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getProviderUid() { return providerUid; }
    public void setProviderUid(String providerUid) { this.providerUid = providerUid; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getPasswordSalt() { return passwordSalt; }
    public void setPasswordSalt(String passwordSalt) { this.passwordSalt = passwordSalt; }

    public boolean isEmailVerified() { return emailVerified; }
    public void setEmailVerified(boolean emailVerified) { this.emailVerified = emailVerified; }

    public String getVerificationCode() { return verificationCode; }
    public void setVerificationCode(String verificationCode) { this.verificationCode = verificationCode; }

    public Long getVerificationExpiresAtMillis() { return verificationExpiresAtMillis; }
    public void setVerificationExpiresAtMillis(Long verificationExpiresAtMillis) { this.verificationExpiresAtMillis = verificationExpiresAtMillis; }

    public String getResetCode() { return resetCode; }
    public void setResetCode(String resetCode) { this.resetCode = resetCode; }

    public Long getResetExpiresAtMillis() { return resetExpiresAtMillis; }
    public void setResetExpiresAtMillis(Long resetExpiresAtMillis) { this.resetExpiresAtMillis = resetExpiresAtMillis; }

    public long getCreatedAtMillis() { return createdAtMillis; }
    public void setCreatedAtMillis(long createdAtMillis) { this.createdAtMillis = createdAtMillis; }
}
