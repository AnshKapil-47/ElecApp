package com.elecsarthi.dto.response;

import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatasetDetailResponse {
    private Long id;
    private String name;
    private String originalFileName;
    private String fileSha256;
    private DatasetTier storageTier;
    private DatasetStatus status;
    private List<String> states;
    private Long supersedesDatasetId;
    private Instant supersededAt;
    private Instant deleteAfter;
    private int totalRows;
    private int importedRows;
    private int failedRows;
    private Long importedByUserId;
    private String importedByUsername;
    private Instant createdAt;
    private Instant completedAt;
    private List<ImportIssueResponse> issues;
}
