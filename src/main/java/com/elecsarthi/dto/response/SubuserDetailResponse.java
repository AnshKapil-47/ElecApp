package com.elecsarthi.dto.response;

import com.elecsarthi.enums.Role;
import com.elecsarthi.enums.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SubuserDetailResponse {
    private Long id;
    private String username;
    private String fullName;
    private Role role;
    private Long customerAccountId;
    private UserStatus status;
    private String contactPhone;
    private List<BoothAssignmentResponse> activeBoothAssignments;
    private Instant createdAt;
}
