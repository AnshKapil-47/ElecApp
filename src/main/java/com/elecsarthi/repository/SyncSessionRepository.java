package com.elecsarthi.repository;

import com.elecsarthi.entity.SyncSession;
import com.elecsarthi.enums.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SyncSessionRepository extends JpaRepository<SyncSession, UUID> {
    Optional<SyncSession> findByIdAndCustomerAccountId(UUID id, Long customerAccountId);
    List<SyncSession> findByCustomerAccountIdAndStatus(Long customerAccountId, SyncStatus status);
}
