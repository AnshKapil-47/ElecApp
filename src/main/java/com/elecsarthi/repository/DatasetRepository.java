package com.elecsarthi.repository;

import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.DatasetStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DatasetRepository extends JpaRepository<Dataset, Long> {
    Optional<Dataset> findByFileSha256AndStatusNot(String fileSha256, DatasetStatus status);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM Dataset d WHERE d.id = :id")
    Optional<Dataset> findByIdForUpdate(@Param("id") Long id);
}
