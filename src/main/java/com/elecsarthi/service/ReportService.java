package com.elecsarthi.service;

import com.elecsarthi.dto.response.SalesReportResponse;
import com.elecsarthi.enums.Role;

import java.time.Instant;

public interface ReportService {
    SalesReportResponse getSalesReport(Long callerUserId, Role callerRole, Long agentId, Instant from, Instant to);
}
