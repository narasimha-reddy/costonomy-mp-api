package com.costonomy.mp.experiment.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.experiment.context.ExperimentContext;
import com.costonomy.mp.experiment.dto.ExperimentDtos;
import com.costonomy.mp.experiment.dto.ExperimentEvaluation;
import com.costonomy.mp.experiment.service.ExperimentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Client-facing API for mobile and frontend web apps to query feature variants and report metrics.
 * Protects internal experimentation details by omitting salts and override subject lists.
 */
@RestController
@RequestMapping("/api/v1/experiments")
@RequiredArgsConstructor
@Tag(name = "Experiments Client", description = "Client-facing Feature Flag and A/B Evaluation API")
public class ExperimentClientController {

    private final ExperimentService experimentService;

    @GetMapping("/active")
    @Operation(summary = "View active experiments", description = "Retrieves all currently active experiments and treatments for client-side discovery without exposing salts or overrides")
    public ApiResponse<List<ExperimentDtos.ClientExperimentDto>> getActiveExperiments() {
        return ApiResponse.ok(experimentService.getActiveClientExperiments());
    }

    @PostMapping("/evaluate")
    @Operation(summary = "Evaluate experiment", description = "Evaluates a feature experiment for the specified subject and returns the assigned variant and dynamic payload")
    public ApiResponse<ExperimentEvaluation> evaluate(@Valid @RequestBody ExperimentDtos.EvaluationRequest request) {
        ExperimentContext context = ExperimentContext.of(
                request.subjectId(),
                request.subjectType(),
                request.attributes()
        );
        ExperimentEvaluation evaluation = experimentService.evaluate(request.experimentKey(), context);
        return ApiResponse.ok(evaluation);
    }

    @PostMapping("/evaluate-batch")
    @Operation(summary = "Batch evaluate experiments", description = "Evaluates multiple feature experiments in a single call (or all active experiments if keys empty) for client app bootstrap")
    public ApiResponse<Map<String, ExperimentEvaluation>> evaluateBatch(@Valid @RequestBody ExperimentDtos.BatchEvaluationRequest request) {
        ExperimentContext context = ExperimentContext.of(
                request.subjectId(),
                request.subjectType(),
                request.attributes()
        );
        Map<String, ExperimentEvaluation> results = experimentService.evaluateBatch(context, request.experimentKeys());
        return ApiResponse.ok(results);
    }

    @PostMapping("/exposures")
    @Operation(summary = "Record client-side exposure", description = "Reports an impression when a UI component actually mounts or renders on the user's screen")
    public ApiResponse<Map<String, String>> recordExposure(@Valid @RequestBody ExperimentDtos.RecordExposureRequest request) {
        experimentService.recordExposure(
                request.experimentKey(),
                request.variantKey(),
                request.subjectId(),
                request.subjectType(),
                request.attributes()
        );
        return ApiResponse.ok(Map.of("status", "recorded", "experimentKey", request.experimentKey()));
    }

    @PostMapping("/metrics")
    @Operation(summary = "Track metric or conversion", description = "Reports a conversion or quantitative metric event for experimentation tracking")
    public ApiResponse<Map<String, Object>> trackMetric(@Valid @RequestBody ExperimentDtos.TrackMetricRequest request) {
        experimentService.trackMetric(
                request.subjectId(),
                request.metricName(),
                request.numericValue(),
                request.properties(),
                request.experimentKey(),
                request.variantKey(),
                request.exposureId()
        );
        return ApiResponse.ok(Map.of("status", "recorded", "metric", request.metricName()));
    }
}
