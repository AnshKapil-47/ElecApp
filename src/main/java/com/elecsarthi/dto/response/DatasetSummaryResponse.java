package com.elecsarthi.dto.response;

import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DatasetSummaryResponse {
    private Long id;
    private String name;
    private String originalFileName;
    private DatasetTier storageTier;
    private DatasetStatus status;
    private int totalRows;
    private int importedRows;
    private int failedRows;
    private Instant deleteAfter;
    private Instant createdAt;
    private Instant completedAt;
}
