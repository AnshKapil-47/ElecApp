package com.elecsarthi.repository;

import com.elecsarthi.entity.AgentSaleLine;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AgentSaleLineRepository extends JpaRepository<AgentSaleLine, Long> {
    List<AgentSaleLine> findBySaleIdOrderByLineNoAsc(Long saleId);
}
