package com.elecsarthi.service;

import com.elecsarthi.entity.Dataset;
import com.elecsarthi.entity.ElectionType;
import com.elecsarthi.enums.DatasetTier;
import com.elecsarthi.enums.DurationUnit;
import com.elecsarthi.service.CriteriaNormalizer.NormalizedCriteria;

import java.time.Instant;

/**
 * The rules shared by Agent sales and direct grants for turning a requested area into something that can be
 * granted: which single dataset holds it, how many voters it has, and when the access starts and ends.
 * Must be called inside the caller's transaction, because the resolved dataset row is locked.
 */
public interface AreaResolver {

    record ValidityWindow(Instant validFrom, Instant validUntil) {
    }

    /**
     * Finds the one IMPORTED dataset of the tier that contains the area and locks it.
     *
     * @throws com.elecsarthi.exception.common.ApiException AREA_UNAVAILABLE when none matches,
     *         AREA_MULTIPLE_DATASETS when several do, AMBIGUOUS_AREA when the election type's guard column
     *         has more than one value inside the area
     */
    Dataset resolveDataset(DatasetTier tier, ElectionType electionType, NormalizedCriteria area);

    int countVoters(Long datasetId, NormalizedCriteria area);

    /**
     * Renewals are back-to-back: access starts when the Customer's current entitlement for the same area
     * ends (or now). Day math runs in Asia/Kolkata. A MASTER entitlement may not outlive its dataset.
     */
    ValidityWindow validityWindow(Long customerAccountId, ElectionType electionType, DatasetTier tier,
                                  NormalizedCriteria area, Dataset dataset, int durationValue,
                                  DurationUnit durationUnit, Instant now);
}
