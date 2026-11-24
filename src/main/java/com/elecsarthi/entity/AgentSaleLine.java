package com.elecsarthi.entity;

import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DurationUnit;
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
@Table(name = "agent_sale_line")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentSaleLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sale_id", nullable = false)
    private Long saleId;

    @Column(name = "line_no", nullable = false)
    private short lineNo;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "dataset_name", nullable = false)
    private String datasetName;

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

    @Column(name = "voter_count_snapshot", nullable = false)
    private int voterCountSnapshot;

    @Column(name = "duration_value", nullable = false)
    private int durationValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_unit", nullable = false)
    private DurationUnit durationUnit;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    @Column(name = "valid_until", nullable = false)
    private Instant validUntil;
}
