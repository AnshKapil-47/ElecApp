package com.elecsarthi.entity;

import com.elecsarthi.enums.DevicePlatform;
import com.elecsarthi.enums.DeviceStatus;
import com.elecsarthi.enums.Role;
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
@Table(name = "user_device")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.AUTO)
    private UUID id;

    @Column(name = "customer_account_id")
    private Long customerAccountId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false)
    private Role userRole;

    @Column(name = "installation_id", nullable = false)
    private String installationId;

    @Column(name = "public_key")
    private String publicKey;

    @Column(name = "key_algorithm")
    @Builder.Default
    private String keyAlgorithm = "ECDSA_P256";

    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false)
    private DevicePlatform platform;

    @Column(name = "device_name")
    private String deviceName;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DeviceStatus status = DeviceStatus.PENDING;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "challenge_hash", columnDefinition = "char(64)")
    private String challengeHash;

    @Column(name = "challenge_purpose")
    private String challengePurpose;

    @Column(name = "challenge_expires_at")
    private Instant challengeExpiresAt;

    @Column(name = "last_request_counter", nullable = false)
    @Builder.Default
    private long lastRequestCounter = 0;

    @Column(name = "replaced_by_device_id")
    private UUID replacedByDeviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;
}
