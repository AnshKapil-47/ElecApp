package com.elecsarthi.repository;

import com.elecsarthi.entity.Voter;
import com.elecsarthi.entity.VoterId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface VoterRepository extends JpaRepository<Voter, VoterId> {
    Optional<Voter> findByDatasetIdAndId(Long datasetId, Long id);

    @Query(value = "SELECT COUNT(*) FROM voter WHERE dataset_id = :datasetId", nativeQuery = true)
    long countByDatasetId(@Param("datasetId") Long datasetId);
}
