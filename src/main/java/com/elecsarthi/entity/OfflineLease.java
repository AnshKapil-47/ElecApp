package com.elecsarthi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "offline_lease")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineLease {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

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
