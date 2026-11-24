package com.elecsarthi.entity;

import com.elecsarthi.enums.ClientType;
import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.SessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
@Table(name = "user_session")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSession implements org.springframework.data.domain.Persistable<UUID> {

    @Id
    private UUID id;

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

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false)
    private Role userRole;

    @Column(name = "device_id")
    private UUID deviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_type", nullable = false)
    private ClientType clientType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SessionStatus status = SessionStatus.ACTIVE;

    @Column(name = "issued_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant issuedAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    @Builder.Default
    private Instant lastSeenAt = Instant.now();

    @Column(name = "idle_expires_at")
    private Instant idleExpiresAt;

    @Column(name = "absolute_expires_at", nullable = false)
    private Instant absoluteExpiresAt;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "refresh_token_hash", columnDefinition = "char(64)")
    private String refreshTokenHash;

    @Column(name = "refresh_token_generation", nullable = false)
    @Builder.Default
    private int refreshTokenGeneration = 1;

    @Column(name = "refresh_token_expires_at")
    private Instant refreshTokenExpiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "terminated_at")
    private Instant terminatedAt;

    @Column(name = "termination_reason")
    private String terminationReason;

    @Column(name = "terminated_by_user_id")
    private Long terminatedByUserId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.INET)
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Column(name = "user_agent")
    private String userAgent;

    public void terminate(Instant at, String reason, Long terminatedByUserId) {
        this.status = SessionStatus.TERMINATED;
        this.terminatedAt = at;
        this.terminationReason = reason;
        this.terminatedByUserId = terminatedByUserId;
    }
}
