package com.elecsarthi.service;

import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.DatasetSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;

public interface DatasetService {

    Page<DatasetSummaryResponse> listDatasets(Pageable pageable);

    DatasetDetailResponse getDataset(Long id);

    void deleteDataset(Long id, Long callerUserId);

    /**
     * A LONG_TERM dataset is kept until its latest ACTIVE entitlement ends plus a grace period (or the grace
     * period from {@code now} when none is left). Call after any change to the dataset's entitlements.
     */
    void recomputeLongTermRetention(Long datasetId, Instant now);
}
