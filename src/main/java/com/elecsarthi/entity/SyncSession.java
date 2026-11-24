package com.elecsarthi.entity;

import com.elecsarthi.enums.SyncStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sync_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncSession {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "customer_account_id", nullable = false)
    private Long customerAccountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "entitlement_id", nullable = false)
    private Long entitlementId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "protocol_version", nullable = false)
    @Builder.Default
    private short protocolVersion = 1;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "manifest_sha256", nullable = false, columnDefinition = "char(64)")
    private String manifestSha256;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "scope_snapshot", columnDefinition = "jsonb", nullable = false)
    private String scopeSnapshot;

    @Column(name = "policy_version", nullable = false)
    private int policyVersion;

    @Column(name = "includes_mobile", nullable = false)
    @Builder.Default
    private boolean includesMobile = false;

    @Column(name = "total_records", nullable = false)
    private int totalRecords;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "chunk_size", nullable = false)
    private int chunkSize;

    @Column(name = "chunks_done", nullable = false)
    @Builder.Default
    private int chunksDone = 0;

    @Column(name = "signing_key_version", nullable = false)
    private short signingKeyVersion;

    @Column(name = "offline_valid_until", nullable = false)
    private Instant offlineValidUntil;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SyncStatus status = SyncStatus.ACTIVE;

    @Column(name = "cancel_reason")
    private String cancelReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "completed_at")
    private Instant completedAt;
}
