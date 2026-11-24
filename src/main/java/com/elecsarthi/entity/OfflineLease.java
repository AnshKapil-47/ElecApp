package com.elecsarthi.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_lease")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineLease implements org.springframework.data.domain.Persistable<UUID> {

    /** Assigned before the lease is signed, because the signed payload names the lease. */
    @Id
    @Builder.Default
    private UUID id = UUID.randomUUID();

    @Transient
    @Builder.Default
    private boolean isNewRecord = true;

    @Override
    public boolean isNew() {
        return isNewRecord;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNewRecord = false;
    }

    @Column(name = "booth_assignment_id", nullable = false)
    private Long boothAssignmentId;

    @Column(name = "subuser_id", nullable = false)
    private Long subuserId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "lease_seq", nullable = false)
    private long leaseSeq;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "payload_sha256", nullable = false, columnDefinition = "char(64)")
    private String payloadSha256;

    @Column(name = "signing_key_version", nullable = false)
    private short signingKeyVersion;

    @Column(name = "issued_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;
}
