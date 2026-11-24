package com.elecsarthi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "device_nonce")
@IdClass(DeviceNonceId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeviceNonce {

    @Id
    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Id
    @Column(name = "nonce", nullable = false)
    private String nonce;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
}
