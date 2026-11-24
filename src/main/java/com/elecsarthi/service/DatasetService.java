package com.elecsarthi.service;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import com.elecsarthi.dto.response.DatasetSummaryResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface DatasetService {

    Page<DatasetSummaryResponse> listDatasets(Pageable pageable);

    DatasetDetailResponse getDataset(Long id);

    void deleteDataset(Long id, Long callerUserId);
}
