package com.elecsarthi.repository;

import com.elecsarthi.entity.OfflineLease;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface OfflineLeaseRepository extends JpaRepository<OfflineLease, UUID> {
    Optional<OfflineLease> findByIdAndSubuserId(UUID id, Long subuserId);
    Optional<OfflineLease> findTopByDeviceIdOrderByLeaseSeqDesc(UUID deviceId);
}
