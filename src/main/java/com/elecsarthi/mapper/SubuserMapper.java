package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.BoothAssignmentResponse;
import com.elecsarthi.dto.response.SubuserDetailResponse;
import com.elecsarthi.entity.AppUser;
import com.elecsarthi.entity.BoothAssignment;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SubuserMapper {

    public BoothAssignmentResponse toAssignmentResponse(BoothAssignment ba) {
        if (ba == null) return null;
        return BoothAssignmentResponse.builder()
                .id(ba.getId())
                .customerAccountId(ba.getCustomerAccountId())
                .subuserId(ba.getSubuserId())
                .entitlementId(ba.getEntitlementId())
                .boothValue(ba.getBoothValue())
                .slot(ba.getSlot())
                .status(ba.getStatus())
                .validFrom(ba.getValidFrom())
                .validUntil(ba.getValidUntil())
                .assignedByUserId(ba.getAssignedByUserId())
                .createdAt(ba.getCreatedAt())
                .build();
    }

    public SubuserDetailResponse toDetailResponse(
            AppUser subuser,
            String contactPhone,
            List<BoothAssignmentResponse> activeBoothAssignments) {
        if (subuser == null) return null;
        return SubuserDetailResponse.builder()
                .id(subuser.getId())
                .username(subuser.getUsername())
                .fullName(subuser.getFullName())
                .role(subuser.getRole())
                .customerAccountId(subuser.getCustomerAccountId())
                .status(subuser.getStatus())
                .contactPhone(contactPhone)
                .activeBoothAssignments(activeBoothAssignments)
                .createdAt(subuser.getCreatedAt())
                .build();
    }
}
