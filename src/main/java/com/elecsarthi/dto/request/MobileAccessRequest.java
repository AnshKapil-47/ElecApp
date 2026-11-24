package com.elecsarthi.dto.request;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MobileAccessRequest {
    private boolean enabled;
}
