package com.costonomy.mp.experiment.service;

import com.costonomy.mp.experiment.domain.EvaluationReason;
import com.costonomy.mp.experiment.domain.SubjectType;

public record ExperimentExposureEvent(
        String experimentKey,
        String variantKey,
        String subjectId,
        SubjectType subjectType,
        EvaluationReason reason,
        String attributesSnapshot
) {
}
