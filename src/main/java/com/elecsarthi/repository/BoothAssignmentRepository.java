package com.elecsarthi.repository;

import com.elecsarthi.entity.BoothAssignment;
import com.elecsarthi.enums.BoothAssignmentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface BoothAssignmentRepository extends JpaRepository<BoothAssignment, Long> {
    List<BoothAssignment> findBySubuserIdAndStatus(Long subuserId, BoothAssignmentStatus status);
    List<BoothAssignment> findByCustomerAccountId(Long customerAccountId);
    List<BoothAssignment> findByEntitlementId(Long entitlementId);
    Optional<BoothAssignment> findByIdAndSubuserId(Long id, Long subuserId);

    @Query("SELECT ba FROM BoothAssignment ba WHERE ba.subuserId = :subuserId " +
           "AND ba.entitlementId = :entitlementId " +
           "AND ba.status = 'ACTIVE' AND ba.validFrom <= :now AND :now < ba.validUntil")
    List<BoothAssignment> findActiveAssignmentsForSubuserAndEntitlement(@Param("subuserId") Long subuserId,
                                                                        @Param("entitlementId") Long entitlementId,
                                                                        @Param("now") Instant now);
}
