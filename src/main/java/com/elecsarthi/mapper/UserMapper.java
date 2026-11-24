package com.elecsarthi.mapper;

import com.elecsarthi.dto.response.UserDetailResponse;
import com.elecsarthi.dto.response.UserSummaryResponse;
import com.elecsarthi.entity.AppUser;
import org.springframework.stereotype.Component;

@Component
public class UserMapper {

    public UserDetailResponse toDetailResponse(AppUser user, int activeSessionCount) {
        if (user == null) return null;
        return UserDetailResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .role(user.getRole())
                .status(user.getStatus())
                .customerAccountId(user.getCustomerAccountId())
                .createdByUserId(user.getCreatedByUserId())
                .mustChangePassword(user.isMustChangePassword())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .activeSessionCount(activeSessionCount)
                .build();
    }

    public UserSummaryResponse toSummaryResponse(AppUser user) {
        if (user == null) return null;
        return UserSummaryResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .fullName(user.getFullName())
                .role(user.getRole())
                .status(user.getStatus())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
