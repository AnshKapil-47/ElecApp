package com.elecsarthi.dto.response;

import com.elecsarthi.enums.TurnoutSource;
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
public class TurnoutResponse {
    private Long customerAccountId;
    private Long voterRowId;
    private boolean voted;
    private Instant markedAt;
    private TurnoutSource source;
}
