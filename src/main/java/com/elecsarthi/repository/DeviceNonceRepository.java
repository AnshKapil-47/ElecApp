package com.elecsarthi.repository;

import com.elecsarthi.entity.DeviceNonce;
import com.elecsarthi.entity.DeviceNonceId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface DeviceNonceRepository extends JpaRepository<DeviceNonce, DeviceNonceId> {
    boolean existsByDeviceIdAndNonce(UUID deviceId, String nonce);

    @Modifying
    @Query("DELETE FROM DeviceNonce dn WHERE dn.expiresAt < :now")
    int purgeExpiredNonces(@Param("now") Instant now);
}
