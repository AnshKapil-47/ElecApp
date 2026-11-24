package com.elecsarthi.entity;

import com.elecsarthi.enums.AuditOutcome;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    @Column(name = "customer_account_id")
    private Long customerAccountId;

    @Column(name = "dataset_id")
    private Long datasetId;

    @Column(name = "device_id")
    private UUID deviceId;

    @Column(name = "action_type", nullable = false)
    private String actionType;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private AuditOutcome outcome;

    @Column(name = "target_entity")
    private String targetEntity;

    @Column(name = "target_id")
    private String targetId;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "details", columnDefinition = "jsonb")
    private String details;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.INET)
    @Column(name = "ip_address", columnDefinition = "inet")
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
