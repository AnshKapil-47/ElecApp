package com.elecsarthi.service;

import com.elecsarthi.dto.request.ProvisionCustomerRequest;
import com.elecsarthi.dto.response.CustomerDetailResponse;
import com.elecsarthi.dto.response.CustomerProvisionResponse;
import com.elecsarthi.dto.response.CustomerSummaryResponse;
import com.elecsarthi.dto.response.ReissueCodeResponse;
import com.elecsarthi.enums.Role;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface CustomerService {

    CustomerProvisionResponse provisionCustomer(ProvisionCustomerRequest request, Long callerUserId, Role callerRole);

    ReissueCodeResponse reissueActivationCode(Long customerId, Long callerUserId, Role callerRole);

    void suspendCustomer(Long customerId, String reason, Long callerUserId, Role callerRole);

    void reactivateCustomer(Long customerId, Long callerUserId, Role callerRole);

    void reassignAgent(Long customerId, Long newAgentId, Long callerUserId);

    Page<CustomerSummaryResponse> listCustomers(Pageable pageable, Long callerUserId, Role callerRole);

    CustomerDetailResponse getCustomer(Long customerId, Long callerUserId, Role callerRole);
}
