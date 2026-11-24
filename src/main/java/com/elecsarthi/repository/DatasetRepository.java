package com.elecsarthi.repository;

import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DatasetRepository extends JpaRepository<Dataset, Long> {
    Optional<Dataset> findByFileSha256AndStatusNot(String fileSha256, DatasetStatus status);
    List<Dataset> findByStorageTierAndStatus(DatasetTier storageTier, DatasetStatus status);

    @Query(value = "SELECT d.* FROM dataset d WHERE d.storage_tier = :tier AND d.status = 'IMPORTED' AND :state = ANY(d.states)", nativeQuery = true)
    List<Dataset> findImportedByTierAndState(@Param("tier") String tier, @Param("state") String state);
}
