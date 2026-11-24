package com.elecsarthi.entity;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementSource;
import com.elecsarthi.enums.EntitlementStatus;
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

@Entity
@Table(name = "entitlement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Entitlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_account_id", nullable = false)
    private Long customerAccountId;

    @Column(name = "election_type_id", nullable = false)
    private Short electionTypeId;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_tier", nullable = false)
    private DatasetTier storageTier;

    @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "criteria", columnDefinition = "jsonb", nullable = false)
    private String criteria;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "criteria_hash", nullable = false, columnDefinition = "char(64)")
    private String criteriaHash;

    @Column(name = "scope_label", nullable = false)
    private String scopeLabel;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private EntitlementSource source;

    @Column(name = "sale_line_id", unique = true)
    private Long saleLineId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private EntitlementStatus status = EntitlementStatus.ACTIVE;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;

    @Column(name = "mobile_access", nullable = false)
    @Builder.Default
    private boolean mobileAccess = false;

    @Column(name = "mobile_access_changed_at")
    private Instant mobileAccessChangedAt;

    @Column(name = "mobile_access_changed_by")
    private Long mobileAccessChangedBy;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    @Column(name = "revoked_by_user_id")
    private Long revokedByUserId;
}
