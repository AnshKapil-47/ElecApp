package com.elecsarthi.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "import_issue")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ImportIssue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Column(name = "sheet")
    private String sheet;

    @Column(name = "row_no")
    private Integer rowNo;

    @Column(name = "column_name")
    private String columnName;

    @Column(name = "issue_code", nullable = false)
    private String issueCode;

    @Column(name = "issue_detail")
    private String issueDetail;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
