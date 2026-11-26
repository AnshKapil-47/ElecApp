package com.elecsarthi.service.impl;

import com.elecsarthi.entity.Dataset;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.repository.DatasetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Removes a dataset completely: its voters and turnout (dropped with their partitions) and every row that depends
 * on it, then the dataset row itself, so nothing about it is left in the database. Sale history is not touched:
 * {@code agent_sale_line} keeps its own snapshots and has no link to the dataset, and {@code audit_log} is append-only.
 *
 * <p>Callers decide <em>whether</em> a dataset may be deleted (no ACTIVE entitlement, no unexpired lease) and write
 * the audit entry. They must call this inside a transaction that holds the dataset row lock; any failure rolls the
 * whole deletion back, partition drop included.
 */
@Component
@RequiredArgsConstructor
class DatasetEraser {

    private static final String DATASET_ENTITLEMENTS = "(SELECT id FROM entitlement WHERE dataset_id = ?)";
    private static final String DATASET_ASSIGNMENTS =
            "(SELECT id FROM booth_assignment WHERE entitlement_id IN " + DATASET_ENTITLEMENTS + ")";

    private final DatasetRepository datasetRepository;
    private final JdbcTemplate jdbcTemplate;

    void erase(Dataset dataset) {
        Long id = dataset.getId();

        // Flag first so a concurrent sale, lease or import cannot pick the dataset up while it is being removed.
        dataset.setStatus(DatasetStatus.DELETING);
        datasetRepository.saveAndFlush(dataset);

        // Children before parents: lease -> assignment -> entitlement, sync sessions -> entitlement.
        jdbcTemplate.update("DELETE FROM offline_lease WHERE booth_assignment_id IN " + DATASET_ASSIGNMENTS, id);
        jdbcTemplate.update("DELETE FROM booth_assignment WHERE entitlement_id IN " + DATASET_ENTITLEMENTS, id);
        jdbcTemplate.update("DELETE FROM sync_session WHERE entitlement_id IN " + DATASET_ENTITLEMENTS, id);
        jdbcTemplate.update("DELETE FROM entitlement WHERE dataset_id = ?", id);
        jdbcTemplate.update("DELETE FROM import_issue WHERE dataset_id = ?", id);

        jdbcTemplate.execute("SELECT detach_and_drop_dataset_partitions(" + id + "::bigint);");

        datasetRepository.delete(dataset);
        datasetRepository.flush();
    }
}
