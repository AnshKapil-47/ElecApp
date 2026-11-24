package com.elecsarthi.service;

import com.elecsarthi.dto.response.CascadingFilterOptionResponse;
import com.elecsarthi.dto.response.ElectionTypeFieldResponse;
import com.elecsarthi.dto.response.ElectionTypeResponse;

import java.util.List;
import java.util.Map;

public interface ElectionTypeService {

    List<ElectionTypeResponse> getAllElectionTypes();

    ElectionTypeResponse getElectionType(String codeOrId);

    List<ElectionTypeFieldResponse> getFields(String codeOrId);

    CascadingFilterOptionResponse getCascadingFilterOptions(String codeOrId, String targetField, Map<String, String> parentFilters);
}
