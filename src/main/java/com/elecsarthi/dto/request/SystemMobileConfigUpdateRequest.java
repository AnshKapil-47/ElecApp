package com.elecsarthi.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemMobileConfigUpdateRequest {
    private boolean enabled;
}
