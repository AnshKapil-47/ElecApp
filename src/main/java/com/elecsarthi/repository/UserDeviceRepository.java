package com.elecsarthi.repository;

import com.elecsarthi.entity.UserDevice;
import com.elecsarthi.enums.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserDeviceRepository extends JpaRepository<UserDevice, UUID> {
    Optional<UserDevice> findByUserIdAndStatus(Long userId, DeviceStatus status);
    Optional<UserDevice> findByInstallationId(String installationId);
    Optional<UserDevice> findByCustomerAccountIdAndInstallationId(Long customerAccountId, String installationId);
    List<UserDevice> findByUserId(Long userId);
}
