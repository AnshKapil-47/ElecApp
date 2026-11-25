package com.elecsarthi.controller;

import com.elecsarthi.dto.response.CascadingFilterOptionResponse;
import com.elecsarthi.dto.response.ElectionTypeFieldResponse;
import com.elecsarthi.dto.response.ElectionTypeResponse;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.service.ElectionTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/election-types")
@RequiredArgsConstructor
@Tag(name = "Election Types", description = "Election type configuration and cascading filter resolution")
public class ElectionTypeController {

    private final ElectionTypeService electionTypeService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get all active election types")
    public ResponseEntity<List<ElectionTypeResponse>> getAll() {
        return ResponseEntity.ok(electionTypeService.getAllElectionTypes());
    }

    @GetMapping("/{codeOrId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get election type details by code or numeric ID")
    public ResponseEntity<ElectionTypeResponse> getByIdOrCode(@PathVariable String codeOrId) {
        return ResponseEntity.ok(electionTypeService.getElectionType(codeOrId));
    }

    @GetMapping("/{codeOrId}/fields")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get field definitions for an election type")
    public ResponseEntity<List<ElectionTypeFieldResponse>> getFields(@PathVariable String codeOrId) {
        return ResponseEntity.ok(electionTypeService.getFields(codeOrId));
    }

    @GetMapping("/{codeOrId}/filters")
    @PreAuthorize("hasAnyRole('OWNER', 'SUBOWNER', 'AGENT')")
    @Operation(summary = "Query available distinct values for cascading sale-form filters (Owner, Subowner, Agent only)",
            description = "Values come only from IMPORTED data of the given storage tier that is usable through the given election type")
    public ResponseEntity<CascadingFilterOptionResponse> getFilterValues(
            @PathVariable String codeOrId,
            @RequestParam("tier") DatasetTier tier,
            @RequestParam("target") String targetField,
            @RequestParam Map<String, String> allParams) {

        // Remove non-filter query parameters
        allParams.remove("target");
        allParams.remove("tier");

        return ResponseEntity.ok(electionTypeService.getCascadingFilterOptions(codeOrId, tier, targetField, allParams));
    }
}
