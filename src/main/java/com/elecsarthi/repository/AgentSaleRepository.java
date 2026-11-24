package com.elecsarthi.repository;

import com.elecsarthi.entity.AgentSale;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AgentSaleRepository extends JpaRepository<AgentSale, Long> {
    Optional<AgentSale> findBySoldByUserIdAndIdempotencyKey(Long soldByUserId, String idempotencyKey);
    List<AgentSale> findBySoldByUserIdOrderBySoldAtDesc(Long soldByUserId);
    List<AgentSale> findByCustomerAccountIdOrderBySoldAtDesc(Long customerAccountId);
}
