package com.elecsarthi.repository;

import com.elecsarthi.entity.AppUser;
import com.elecsarthi.enums.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
    boolean existsByUsername(String username);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findByIdForUpdate(@Param("id") Long id);

    Optional<AppUser> findByCustomerAccountIdAndRole(Long customerAccountId, Role role);

    List<AppUser> findByCustomerAccountId(Long customerAccountId);

    List<AppUser> findByRole(Role role);
    Page<AppUser> findByRole(Role role, Pageable pageable);
}
