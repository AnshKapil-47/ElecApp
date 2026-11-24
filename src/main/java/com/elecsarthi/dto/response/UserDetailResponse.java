package com.elecsarthi.dto.response;

import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDetailResponse {
    private Long id;
    private String username;
    private String fullName;
    private Role role;
    private UserStatus status;
    private Long customerAccountId;
    private Long createdByUserId;
    private boolean mustChangePassword;
    private Instant createdAt;
    private Instant updatedAt;
    private int activeSessionCount;
}
