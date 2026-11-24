package com.elecsarthi.repository;

import com.elecsarthi.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByActorUserIdOrderByCreatedAtDesc(Long actorUserId);
    List<AuditLog> findByCustomerAccountIdOrderByCreatedAtDesc(Long customerAccountId);
}
