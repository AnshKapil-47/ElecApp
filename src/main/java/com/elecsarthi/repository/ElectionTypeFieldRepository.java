package com.elecsarthi.repository;

import com.elecsarthi.entity.ElectionTypeField;
import com.elecsarthi.enums.FieldUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElectionTypeFieldRepository extends JpaRepository<ElectionTypeField, Long> {
    List<ElectionTypeField> findByElectionTypeIdOrderByDisplayOrderAsc(Short electionTypeId);
    List<ElectionTypeField> findByElectionTypeIdAndUsageOrderByFilterOrderAsc(Short electionTypeId, FieldUsage usage);
    List<ElectionTypeField> findByElectionTypeIdAndOnSlipTrueOrderByDisplayOrderAsc(Short electionTypeId);
    Optional<ElectionTypeField> findByElectionTypeIdAndSourceColumn(Short electionTypeId, String sourceColumn);
}
