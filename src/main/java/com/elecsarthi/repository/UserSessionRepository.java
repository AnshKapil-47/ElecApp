package com.elecsarthi.repository;

import com.elecsarthi.entity.UserSession;
import com.elecsarthi.enums.SessionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, UUID> {
    Optional<UserSession> findByIdAndStatus(UUID id, SessionStatus status);
    List<UserSession> findByUserIdAndStatus(Long userId, SessionStatus status);
    long countByUserIdAndStatus(Long userId, SessionStatus status);

    @Modifying
    @Query("UPDATE UserSession s SET s.status = :status, s.terminatedAt = :now, s.terminationReason = :reason, s.terminatedByUserId = :byUser WHERE s.deviceId = :deviceId AND s.status = 'ACTIVE'")
    void terminateAllActiveByDeviceId(@Param("deviceId") UUID deviceId, @Param("status") SessionStatus status, @Param("now") Instant now, @Param("reason") String reason, @Param("byUser") Long byUser);
}
