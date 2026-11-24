package com.elecsarthi.dto.response;

import com.elecsarthi.enums.TurnoutSource;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TurnoutResponse {
    private Long datasetId;
    private Long customerAccountId;
    private Long voterRowId;
    private boolean voted;
    private Instant markedAt;
    private TurnoutSource source;
}
