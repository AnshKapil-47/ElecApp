package com.elecsarthi.service.impl;

import java.util.Locale;
import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.entity.Entitlement;
import com.elecsarthi.enums.DatasetStatus;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DurationUnit;
import com.elecsarthi.exception.common.ApiException;
import com.elecsarthi.exception.common.BadRequestException;
import com.elecsarthi.exception.common.ResourceNotFoundException;
import com.elecsarthi.exception.errorcode.ErrorCode;
import com.elecsarthi.repository.DatasetRepository;
import com.elecsarthi.repository.EntitlementRepository;
import com.elecsarthi.service.AreaResolver;
import com.elecsarthi.service.CriteriaNormalizer.NormalizedCriteria;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AreaResolverImpl implements AreaResolver {

    private static final ZoneId KOLKATA_ZONE = ZoneId.of("Asia/Kolkata");

    private final DatasetRepository datasetRepository;
    private final EntitlementRepository entitlementRepository;
    private final JdbcTemplate jdbcTemplate;

    @Override
    public Dataset resolveDataset(DatasetTier tier, ElectionType electionType, NormalizedCriteria area) {
        List<Object> params = new ArrayList<>();
        params.add(tier.name());
        String sql = "SELECT DISTINCT v.dataset_id FROM voter v JOIN dataset d ON d.id = v.dataset_id "
                + "WHERE d.storage_tier = ? AND d.status = 'IMPORTED'" + criteriaPredicate("v.", area.getCriteriaMap(), params);
        List<Long> datasetIds = jdbcTemplate.query(sql, (rs, rowNum) -> rs.getLong(1), params.toArray());

        if (datasetIds.isEmpty()) {
            throw new ApiException(ErrorCode.AREA_UNAVAILABLE, "Area not available for storage tier " + tier, HttpStatus.BAD_REQUEST);
        }
        if (datasetIds.size() > 1) {
            throw new ApiException(ErrorCode.AREA_MULTIPLE_DATASETS, "Multiple datasets found for this area in tier " + tier, HttpStatus.BAD_REQUEST);
        }
        Long datasetId = datasetIds.get(0);
        Dataset dataset = datasetRepository.findByIdForUpdate(datasetId)
                .orElseThrow(() -> new ResourceNotFoundException("Resolved dataset not found: " + datasetId));
        if (dataset.getStatus() != DatasetStatus.IMPORTED) {
            throw new ApiException(ErrorCode.AREA_UNAVAILABLE, "Dataset is not in IMPORTED state: " + dataset.getStatus(), HttpStatus.BAD_REQUEST);
        }
        rejectAmbiguousArea(electionType, datasetId, area);
        return dataset;
    }

    /** Local-body types are ambiguous if the area's voters carry more than one value of the type's guard column. */
    private void rejectAmbiguousArea(ElectionType electionType, Long datasetId, NormalizedCriteria area) {
        if (electionType.getGuardColumn() == null || electionType.getGuardColumn().isBlank()) {
            return;
        }
        String guardColumn = electionType.getGuardColumn().trim().toLowerCase(Locale.ROOT);
        List<Object> params = new ArrayList<>();
        params.add(datasetId);
        Integer distinctValues = jdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT " + guardColumn + ") FROM voter WHERE dataset_id = ?"
                        + criteriaPredicate("", area.getCriteriaMap(), params),
                Integer.class, params.toArray());
        if (distinctValues != null && distinctValues > 1) {
            throw new ApiException(ErrorCode.AMBIGUOUS_AREA,
                    "Area contains multiple " + guardColumn + " values; ambiguous area", HttpStatus.BAD_REQUEST);
        }
    }

    @Override
    public int countVoters(Long datasetId, NormalizedCriteria area) {
        List<Object> params = new ArrayList<>();
        params.add(datasetId);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM voter WHERE dataset_id = ?" + criteriaPredicate("", area.getCriteriaMap(), params),
                Integer.class, params.toArray());
        return count != null ? count : 0;
    }

    @Override
    public ValidityWindow validityWindow(Long customerAccountId, ElectionType electionType, DatasetTier tier,
                                         NormalizedCriteria area, Dataset dataset, int durationValue,
                                         DurationUnit durationUnit, Instant now) {
        Instant validFrom = entitlementRepository
                .findLatestActiveForArea(customerAccountId, electionType.getId(), tier, area.getCriteriaHash())
                .stream().findFirst()
                .map(Entitlement::getValidUntil)
                .filter(currentEnd -> currentEnd.isAfter(now))
                .orElse(now);

        ZonedDateTime start = validFrom.atZone(KOLKATA_ZONE);
        ZonedDateTime end = switch (durationUnit) {
            case DAY -> start.plusDays(durationValue);
            case WEEK -> start.plusWeeks(durationValue);
            case MONTH -> start.plusMonths(durationValue);
        };
        Instant validUntil = end.toInstant();

        if (tier == DatasetTier.MASTER && dataset.getDeleteAfter() != null && validUntil.isAfter(dataset.getDeleteAfter())) {
            throw new BadRequestException("Validity until " + validUntil + " exceeds MASTER dataset expiration " + dataset.getDeleteAfter());
        }
        return new ValidityWindow(validFrom, validUntil);
    }

    /**
     * Appends " AND column = ?" for every criterion and adds the values to {@code params}. The column names are
     * the keys of normalized criteria, which CriteriaNormalizer has already checked against the election type's
     * filter fields; values are always bind parameters.
     */
    private static String criteriaPredicate(String columnPrefix, Map<String, Object> criteria, List<Object> params) {
        StringBuilder predicate = new StringBuilder();
        criteria.forEach((column, value) -> {
            predicate.append(" AND ").append(columnPrefix).append(column).append(" = ?");
            params.add(value);
        });
        return predicate.toString();
    }
}
