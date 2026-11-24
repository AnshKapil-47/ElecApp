package com.elecsarthi.dto.response;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineWritebackResponse {
    private int totalSubmitted;
    private int totalAccepted;
    private int totalRejected;
}
