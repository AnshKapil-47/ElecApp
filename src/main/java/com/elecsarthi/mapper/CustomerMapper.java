package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.CustomerDetailResponse;
import com.elecsarthi.dto.response.CustomerSummaryResponse;
import com.elecsarthi.entity.CustomerAccount;
import org.springframework.stereotype.Component;

@Component
public class CustomerMapper {

    public CustomerDetailResponse toDetailResponse(CustomerAccount ca, String owningAgentName, String phoneToDisplay) {
        if (ca == null) return null;
        return CustomerDetailResponse.builder()
                .id(ca.getId())
                .customerName(ca.getCustomerName())
                .partyOrOrgName(ca.getPartyOrOrgName())
                .contactPhone(phoneToDisplay != null ? phoneToDisplay : ca.getContactPhone())
                .contactEmail(ca.getContactEmail())
                .status(ca.getStatus())
                .activationStatus(ca.getActivationStatus())
                .owningAgentUserId(ca.getOwningAgentUserId())
                .owningAgentName(owningAgentName)
                .createdByUserId(ca.getCreatedByUserId())
                .activatedAt(ca.getActivatedAt())
                .suspendedAt(ca.getSuspendedAt())
                .suspensionReason(ca.getSuspensionReason())
                .mobileAccessPolicyVersion(ca.getMobileAccessPolicyVersion())
                .createdAt(ca.getCreatedAt())
                .build();
    }

    public CustomerSummaryResponse toSummaryResponse(CustomerAccount ca, String owningAgentName, String phoneToDisplay) {
        if (ca == null) return null;
        return CustomerSummaryResponse.builder()
                .id(ca.getId())
                .customerName(ca.getCustomerName())
                .partyOrOrgName(ca.getPartyOrOrgName())
                .contactPhone(phoneToDisplay != null ? phoneToDisplay : ca.getContactPhone())
                .status(ca.getStatus())
                .activationStatus(ca.getActivationStatus())
                .owningAgentUserId(ca.getOwningAgentUserId())
                .owningAgentName(owningAgentName)
                .createdAt(ca.getCreatedAt())
                .build();
    }
}
