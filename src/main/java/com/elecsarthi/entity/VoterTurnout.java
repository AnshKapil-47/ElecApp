package com.elecsarthi.entity;

import com.elecsarthi.enums.TurnoutSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "voter_turnout")
@IdClass(VoterTurnoutId.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VoterTurnout {

    @Id
    @Column(name = "dataset_id", nullable = false)
    private Long datasetId;

    @Id
    @Column(name = "customer_account_id", nullable = false)
    private Long customerAccountId;

    @Id
    @Column(name = "voter_row_id", nullable = false)
    private Long voterRowId;

    @Column(name = "marked_by_user_id")
    private Long markedByUserId;

    @Column(name = "marked_at", nullable = false)
    @Builder.Default
    private Instant markedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false)
    private TurnoutSource source;
}
