package com.elecsarthi.entity;

import com.elecsarthi.enums.ActivationStatus;
import com.elecsarthi.enums.UserStatus;
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
@Table(name = "customer_account")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CustomerAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "created_by_user_id", nullable = false)
    private Long createdByUserId;

    @Column(name = "owning_agent_user_id")
    private Long owningAgentUserId;

    @Column(name = "customer_name", nullable = false)
    private String customerName;

    @Column(name = "party_or_org_name")
    private String partyOrOrgName;

    @Column(name = "contact_phone", nullable = false, unique = true)
    private String contactPhone;

    @Column(name = "contact_email", columnDefinition = "citext")
    private String contactEmail;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "activation_status", nullable = false)
    @Builder.Default
    private ActivationStatus activationStatus = ActivationStatus.PENDING;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "activation_code_hash", columnDefinition = "char(64)")
    private String activationCodeHash;

    @Column(name = "activation_code_key_version")
    private Short activationCodeKeyVersion;

    @Column(name = "activation_code_issued_at")
    private Instant activationCodeIssuedAt;

    @Column(name = "activation_code_expires_at")
    private Instant activationCodeExpiresAt;

    @Column(name = "activation_code_issued_by_user_id")
    private Long activationCodeIssuedByUserId;

    @Column(name = "activation_attempt_count", nullable = false)
    @Builder.Default
    private short activationAttemptCount = 0;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

    @Column(name = "suspension_reason")
    private String suspensionReason;

    @Column(name = "suspended_by_user_id")
    private Long suspendedByUserId;

    @Column(name = "mobile_access_policy_version", nullable = false)
    @Builder.Default
    private int mobileAccessPolicyVersion = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private Instant updatedAt = Instant.now();

    public boolean isOwnedByAgent(Long agentUserId) {
        return owningAgentUserId != null && owningAgentUserId.equals(agentUserId);
    }

    /** Tells the Customer's apps to re-check what they may show (for example mobile-number access). */
    public void bumpMobileAccessPolicyVersion() {
        this.mobileAccessPolicyVersion++;
    }
}
