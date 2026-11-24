package com.elecsarthi.repository;

import com.elecsarthi.entity.VoterTurnout;
import com.elecsarthi.entity.VoterTurnoutId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoterTurnoutRepository extends JpaRepository<VoterTurnout, VoterTurnoutId> {
    boolean existsByDatasetIdAndCustomerAccountIdAndVoterRowId(Long datasetId, Long customerAccountId, Long voterRowId);
}
