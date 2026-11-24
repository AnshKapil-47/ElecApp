package com.elecsarthi.repository;

import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.EntitlementStatus;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EntitlementRepository extends JpaRepository<Entitlement, Long> {
    Optional<Entitlement> findByIdAndCustomerAccountId(Long id, Long customerAccountId);
    Optional<Entitlement> findBySaleLineId(Long saleLineId);
    List<Entitlement> findByCustomerAccountId(Long customerAccountId);
    List<Entitlement> findByCustomerAccountIdAndStatus(Long customerAccountId, EntitlementStatus status);
    List<Entitlement> findByDatasetId(Long datasetId);

    @Query("SELECT e FROM Entitlement e WHERE e.customerAccountId = :customerAccountId " +
           "AND e.electionTypeId = :electionTypeId " +
           "AND e.storageTier = :storageTier " +
           "AND e.criteriaHash = :criteriaHash " +
           "AND e.status = 'ACTIVE' " +
           "ORDER BY e.validUntil DESC")
    List<Entitlement> findLatestActiveForArea(@Param("customerAccountId") Long customerAccountId,
                                            @Param("electionTypeId") Short electionTypeId,
                                            @Param("storageTier") DatasetTier storageTier,
                                            @Param("criteriaHash") String criteriaHash);

    default Entitlement requireById(Long id) {
        return findById(id).orElseThrow(() -> new ResourceNotFoundException("Entitlement not found"));
    }
}
