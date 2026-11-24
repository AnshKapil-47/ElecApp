package com.elecsarthi.repository;

import com.elecsarthi.entity.VoterTurnout;
import com.elecsarthi.entity.VoterTurnoutId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VoterTurnoutRepository extends JpaRepository<VoterTurnout, VoterTurnoutId> {
    Optional<VoterTurnout> findByDatasetIdAndCustomerAccountIdAndVoterRowId(Long datasetId, Long customerAccountId, Long voterRowId);
    boolean existsByDatasetIdAndCustomerAccountIdAndVoterRowId(Long datasetId, Long customerAccountId, Long voterRowId);

    @Modifying
    @Query(value = "INSERT INTO voter_turnout (dataset_id, customer_account_id, voter_row_id, marked_by_user_id, source) " +
                   "VALUES (:datasetId, :customerAccountId, :voterRowId, :markedByUserId, :source) " +
                   "ON CONFLICT DO NOTHING", nativeQuery = true)
    int markTurnoutIdempotent(@Param("datasetId") Long datasetId,
                              @Param("customerAccountId") Long customerAccountId,
                              @Param("voterRowId") Long voterRowId,
                              @Param("markedByUserId") Long markedByUserId,
                              @Param("source") String source);

    @Modifying
    @Query(value = "DELETE FROM voter_turnout WHERE dataset_id = :datasetId AND customer_account_id = :customerAccountId AND voter_row_id = :voterRowId", nativeQuery = true)
    int unmarkTurnout(@Param("datasetId") Long datasetId,
                      @Param("customerAccountId") Long customerAccountId,
                      @Param("voterRowId") Long voterRowId);
}
