package com.elecsarthi.repository;

import com.elecsarthi.entity.ImportIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ImportIssueRepository extends JpaRepository<ImportIssue, Long> {
    List<ImportIssue> findByDatasetIdOrderByRowNoAsc(Long datasetId);
}
