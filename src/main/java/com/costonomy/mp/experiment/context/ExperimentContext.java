package com.costonomy.mp.experiment.context;

import com.costonomy.mp.experiment.domain.SubjectType;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Context passed during experiment evaluation containing subject identifier and runtime attributes.
 */
@Getter
@Builder
public class ExperimentContext {

    private final String subjectId;
    @Builder.Default
    private final SubjectType subjectType = SubjectType.USER;
    @Singular
    private final Map<String, Object> attributes;

    public static ExperimentContext of(String subjectId) {
        return ExperimentContext.builder()
                .subjectId(subjectId)
                .subjectType(SubjectType.USER)
                .attributes(Collections.emptyMap())
                .build();
    }

    public static ExperimentContext of(String subjectId, SubjectType subjectType, Map<String, Object> attributes) {
        return ExperimentContext.builder()
                .subjectId(subjectId)
                .subjectType(subjectType != null ? subjectType : SubjectType.USER)
                .attributes(attributes != null ? new HashMap<>(attributes) : Collections.emptyMap())
                .build();
    }

    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }
}
