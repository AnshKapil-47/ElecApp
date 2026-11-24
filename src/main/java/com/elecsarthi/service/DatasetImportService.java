package com.elecsarthi.service;

import com.elecsarthi.dto.request.DatasetUploadRequest;
import com.elecsarthi.dto.response.DatasetDetailResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DatasetImportService {

    DatasetDetailResponse importDataset(MultipartFile file, DatasetUploadRequest request, Long callerUserId);
}
