package com.costonomy.mp.experiment.dto;

import com.costonomy.mp.experiment.domain.Experiment;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.domain.ExperimentVariant;
import com.costonomy.mp.experiment.domain.SubjectType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ExperimentDtos {

    private ExperimentDtos() {
    }

    public record ExperimentVariantDto(
            Long id,
            String variantKey,
            String name,
            String description,
            int weight,
            boolean isControl,
            String payloadJson
    ) {
        public static ExperimentVariantDto fromEntity(ExperimentVariant v) {
            return new ExperimentVariantDto(
                    v.getId(),
                    v.getVariantKey(),
                    v.getName(),
                    v.getDescription(),
                    v.getWeight(),
                    v.isControl(),
                    v.getPayloadJson()
            );
        }
    }

    public record ExperimentDto(
            Long id,
            String experimentKey,
            String name,
            String description,
            ExperimentStatus status,
            SubjectType subjectType,
            int trafficAllocationPct,
            String salt,
            String layer,
            String targetingRules,
            String overrides,
            Instant startTime,
            Instant endTime,
            List<ExperimentVariantDto> variants,
            Instant createdAt,
            Instant updatedAt
    ) {
        public static ExperimentDto fromEntity(Experiment e) {
            List<ExperimentVariantDto> variantDtos = e.getVariants() != null
                    ? e.getVariants().stream().map(ExperimentVariantDto::fromEntity).toList()
                    : List.of();

            return new ExperimentDto(
                    e.getId(),
                    e.getExperimentKey(),
                    e.getName(),
                    e.getDescription(),
                    e.getStatus(),
                    e.getSubjectType(),
                    e.getTrafficAllocationPct(),
                    e.getSalt(),
                    e.getLayer(),
                    e.getTargetingRules(),
                    e.getOverrides(),
                    e.getStartTime(),
                    e.getEndTime(),
                    variantDtos,
                    e.getCreatedAt(),
                    e.getUpdatedAt()
            );
        }
    }

    public record CreateVariantRequest(
            @NotBlank @Size(max = 64) String variantKey,
            @NotBlank @Size(max = 128) String name,
            String description,
            @Min(0) Integer weight,
            Boolean isControl,
            String payloadJson
    ) {}

    public record CreateExperimentRequest(
            @NotBlank @Size(max = 64) String experimentKey,
            @NotBlank @Size(max = 128) String name,
            String description,
            SubjectType subjectType,
            @Min(0) @Max(100) Integer trafficAllocationPct,
            String layer,
            String targetingRules,
            String overrides,
            Instant startTime,
            Instant endTime,
            @NotNull List<CreateVariantRequest> variants
    ) {}

    public record UpdateExperimentRequest(
            @NotBlank @Size(max = 128) String name,
            String description,
            @Min(0) @Max(100) Integer trafficAllocationPct,
            String layer,
            String targetingRules,
            String overrides,
            Instant startTime,
            Instant endTime,
            List<CreateVariantRequest> variants
    ) {}

    public record UpdateStatusRequest(
            @NotNull ExperimentStatus status
    ) {}

    public record AddOverrideRequest(
            @NotBlank String variantKey,
            @NotNull List<String> subjectIds
    ) {}

    public record EvaluationRequest(
            @NotBlank String experimentKey,
            @NotBlank String subjectId,
            SubjectType subjectType,
            Map<String, Object> attributes
    ) {}

    public record BatchEvaluationRequest(
            @NotBlank String subjectId,
            SubjectType subjectType,
            Map<String, Object> attributes,
            List<String> experimentKeys
    ) {}

    public record ClientVariantDto(
            String variantKey,
            String name,
            String description,
            boolean isControl,
            String payloadJson
    ) {
        public static ClientVariantDto fromEntity(ExperimentVariant v) {
            return new ClientVariantDto(
                    v.getVariantKey(),
                    v.getName(),
                    v.getDescription(),
                    v.isControl(),
                    v.getPayloadJson()
            );
        }
    }

    public record ClientExperimentDto(
            Long id,
            String experimentKey,
            String name,
            String description,
            SubjectType subjectType,
            List<ClientVariantDto> variants
    ) {
        public static ClientExperimentDto fromEntity(Experiment e) {
            List<ClientVariantDto> variantDtos = e.getVariants() != null
                    ? e.getVariants().stream().map(ClientVariantDto::fromEntity).toList()
                    : List.of();

            return new ClientExperimentDto(
                    e.getId(),
                    e.getExperimentKey(),
                    e.getName(),
                    e.getDescription(),
                    e.getSubjectType(),
                    variantDtos
            );
        }
    }

    public record TrackMetricRequest(
            @NotBlank String subjectId,
            @NotBlank String metricName,
            BigDecimal numericValue,
            Map<String, Object> properties,
            String experimentKey,
            String variantKey,
            Long exposureId
    ) {
        public TrackMetricRequest(String subjectId, String metricName, BigDecimal numericValue, Map<String, Object> properties) {
            this(subjectId, metricName, numericValue, properties, null, null, null);
        }
    }

    public record RecordExposureRequest(
            @NotBlank String experimentKey,
            @NotBlank String variantKey,
            @NotBlank String subjectId,
            SubjectType subjectType,
            Map<String, Object> attributes
    ) {}

    public record VariantAnalyticsDto(
            String variantKey,
            String variantName,
            boolean isControl,
            long totalExposures,
            long uniqueSubjects,
            long conversionCount,
            long uniqueConvertedSubjects,
            BigDecimal totalValue,
            Double averageValue,
            double conversionRatePct
    ) {}

    public record ExperimentAnalyticsResponse(
            String experimentKey,
            String metricName,
            Instant since,
            List<VariantAnalyticsDto> variants
    ) {}

    public record InspectExperimentRequest(
            @NotBlank String subjectId,
            SubjectType subjectType,
            Map<String, Object> attributes
    ) {}

    public record ExposureRecordDto(
            Long id,
            String experimentKey,
            String variantKey,
            String subjectId,
            String subjectType,
            String evaluationReason,
            String attributesSnapshot,
            Instant exposedAt
    ) {
        public static ExposureRecordDto fromEntity(com.costonomy.mp.experiment.domain.ExperimentExposure e) {
            return new ExposureRecordDto(
                    e.getId(),
                    e.getExperimentKey(),
                    e.getVariantKey(),
                    e.getSubjectId(),
                    e.getSubjectType(),
                    e.getEvaluationReason(),
                    e.getAttributesSnapshot(),
                    e.getExposedAt()
            );
        }
    }
}
