package com.elecsarthi.repository;

import com.elecsarthi.entity.CustomerAccount;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerAccountRepository extends JpaRepository<CustomerAccount, Long> {
    Optional<CustomerAccount> findByContactPhone(String contactPhone);
    boolean existsByContactPhone(String contactPhone);
    List<CustomerAccount> findByOwningAgentUserId(Long owningAgentUserId);
    Page<CustomerAccount> findByOwningAgentUserId(Long owningAgentUserId, Pageable pageable);

    default CustomerAccount requireById(Long id) {
        return findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer account not found"));
    }
}
