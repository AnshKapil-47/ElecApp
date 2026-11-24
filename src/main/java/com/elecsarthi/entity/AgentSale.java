package com.elecsarthi.entity;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.SaleStatus;
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
@Table(name = "agent_sale")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSale {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sold_by_user_id", nullable = false)
    private Long soldByUserId;

    @Column(name = "customer_account_id", nullable = false)
    private Long customerAccountId;

    @Column(name = "election_type_id", nullable = false)
    private Short electionTypeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_tier", nullable = false)
    private DatasetTier storageTier;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "request_sha256", nullable = false, columnDefinition = "char(64)")
    private String requestSha256;

    @Column(name = "sold_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant soldAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private SaleStatus status = SaleStatus.COMPLETED;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "voided_by_user_id")
    private Long voidedByUserId;
}
