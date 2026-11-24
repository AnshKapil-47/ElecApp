package com.elecsarthi.entity;

import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "dataset")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Dataset {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "original_file_name", nullable = false)
    private String originalFileName;

    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    @Column(name = "file_sha256", nullable = false, columnDefinition = "char(64)")
    private String fileSha256;

    @Enumerated(EnumType.STRING)
    @Column(name = "storage_tier", nullable = false)
    private DatasetTier storageTier;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private DatasetStatus status = DatasetStatus.PENDING;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "states")
    private List<String> states;

    @Column(name = "supersedes_dataset_id")
    private Long supersedesDatasetId;

    @Column(name = "superseded_at")
    private Instant supersededAt;

    @Column(name = "delete_after", nullable = false)
    private Instant deleteAfter;

    @Column(name = "total_rows")
    @Builder.Default
    private int totalRows = 0;

    @Column(name = "imported_rows")
    @Builder.Default
    private int importedRows = 0;

    @Column(name = "failed_rows")
    @Builder.Default
    private int failedRows = 0;

    @Column(name = "imported_by_user_id", nullable = false)
    private Long importedByUserId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;
}
