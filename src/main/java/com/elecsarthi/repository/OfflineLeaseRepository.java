package com.elecsarthi.repository;

import com.elecsarthi.entity.OfflineLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfflineLeaseRepository extends JpaRepository<OfflineLease, UUID> {
    Optional<OfflineLease> findByIdAndSubuserId(UUID id, Long subuserId);
    Optional<OfflineLease> findTopByDeviceIdOrderByLeaseSeqDesc(UUID deviceId);

    /** Revokes every still-valid lease of every booth assignment under the entitlement. */
    @Modifying(flushAutomatically = true)
    @Query("UPDATE OfflineLease l SET l.revokedAt = :now, l.revokedByUserId = :revokedByUserId "
            + "WHERE l.revokedAt IS NULL AND l.boothAssignmentId IN "
            + "(SELECT b.id FROM BoothAssignment b WHERE b.entitlementId = :entitlementId)")
    int revokeActiveByEntitlementId(@Param("entitlementId") Long entitlementId,
                                    @Param("now") Instant now,
                                    @Param("revokedByUserId") Long revokedByUserId);
}
