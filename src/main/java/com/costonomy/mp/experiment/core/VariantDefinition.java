package com.costonomy.mp.experiment.core;

import lombok.Builder;
import lombok.Getter;

/**
 * Decoupled, framework-agnostic definition of an experiment variant.
 */
@Getter
@Builder
public class VariantDefinition {

    private final String variantKey;
    private final String name;
    private final String description;
    @Builder.Default
    private final int weight = 50;
    @Builder.Default
    private final boolean control = false;
    private final String payloadJson;
}
