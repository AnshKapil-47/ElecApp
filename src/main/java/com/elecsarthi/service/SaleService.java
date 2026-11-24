package com.elecsarthi.service;

import com.elecsarthi.dto.request.CreateSaleRequest;
import com.elecsarthi.dto.request.VoidSaleRequest;
import com.elecsarthi.dto.response.SaleResponse;
import com.elecsarthi.enums.Role;

import java.util.List;

public interface SaleService {
    SaleResponse createSale(Long agentUserId, CreateSaleRequest request);
    SaleResponse voidSale(Long ownerUserId, Long saleId, VoidSaleRequest request);
    SaleResponse getSale(Long callerUserId, Role callerRole, Long saleId);
    List<SaleResponse> getSalesByAgent(Long agentUserId);
    List<SaleResponse> getSalesByCustomer(Long callerUserId, Role callerRole, Long customerAccountId);
}
