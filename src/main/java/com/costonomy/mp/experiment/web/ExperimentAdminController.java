package com.costonomy.mp.experiment.web;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.experiment.domain.ExperimentStatus;
import com.costonomy.mp.experiment.dto.ExperimentDtos;
import com.costonomy.mp.experiment.service.ExperimentService;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Admin API for managing feature flags, A/B testing experiments, variant allocations, and viewing experiment analytics.
 * Strictly protected using the project's established AccessControlService authorization pattern:
 * CONFIG_VIEW permission for read and inspection endpoints, and CONFIG_MANAGE permission for mutation endpoints.
 */
@RestController
@RequestMapping("/api/v1/admin/experiments")
@RequiredArgsConstructor
@Tag(name = "Experiments Admin", description = "A/B Testing and Rapid Feature Experimentation Management")
public class ExperimentAdminController {

    private final ExperimentService experimentService;
    private final AccessControlService accessControl;

    private Long requirePermission(String permission) {
        Long actorId = ActorContext.requireUserId();
        accessControl.require(actorId, permission, ScopeType.PLATFORM, null);
        return actorId;
    }

    @GetMapping
    @Operation(summary = "List and view experiments", description = "Retrieves experiments with optional filtering. Requires CONFIG_VIEW.")
    public ApiResponse<List<ExperimentDtos.ExperimentDto>> listExperiments(
            @RequestParam(required = false) ExperimentStatus status,
            @RequestParam(required = false) com.costonomy.mp.experiment.domain.SubjectType subjectType,
            @RequestParam(required = false) String search) {
        requirePermission(Permissions.CONFIG_VIEW);
        return ApiResponse.ok(experimentService.listExperiments(status, subjectType, search));
    }

    @GetMapping("/live")
    @Operation(summary = "View live experiments snapshot", description = "Retrieves live in-memory experiment status. Requires CONFIG_VIEW.")
    public ApiResponse<com.costonomy.mp.experiment.dto.LiveExperimentSnapshot> getLiveSnapshot() {
        requirePermission(Permissions.CONFIG_VIEW);
        return ApiResponse.ok(experimentService.getLiveSnapshot());
    }

    @PostMapping("/{experimentKey}/inspect")
    @Operation(summary = "Inspect / preview experiment evaluation", description = "Simulates how a subject is evaluated. Requires CONFIG_VIEW.")
    public ApiResponse<com.costonomy.mp.experiment.dto.InspectionResult> inspect(
            @PathVariable String experimentKey,
            @Valid @RequestBody ExperimentDtos.InspectExperimentRequest request) {
        requirePermission(Permissions.CONFIG_VIEW);
        return ApiResponse.ok(experimentService.inspect(
                experimentKey,
                request.subjectId(),
                request.subjectType(),
                request.attributes()
        ));
    }

    @GetMapping("/{experimentKey}/exposures")
    @Operation(summary = "View recent exposures", description = "Retrieves the recent live exposure log for an experiment. Requires CONFIG_VIEW.")
    public ApiResponse<List<ExperimentDtos.ExposureRecordDto>> getRecentExposures(@PathVariable String experimentKey) {
        requirePermission(Permissions.CONFIG_VIEW);
        return ApiResponse.ok(experimentService.getRecentExposures(experimentKey));
    }

    @GetMapping("/{experimentKey}")
    @Operation(summary = "Get experiment details", description = "Retrieves detailed configuration for a specific experiment key. Requires CONFIG_VIEW.")
    public ApiResponse<ExperimentDtos.ExperimentDto> getExperiment(@PathVariable String experimentKey) {
        requirePermission(Permissions.CONFIG_VIEW);
        return ApiResponse.ok(experimentService.getExperiment(experimentKey));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create an experiment", description = "Creates a new experiment with variants and targeting rules. Requires CONFIG_MANAGE.")
    public ApiResponse<ExperimentDtos.ExperimentDto> createExperiment(@Valid @RequestBody ExperimentDtos.CreateExperimentRequest request) {
        requirePermission(Permissions.CONFIG_MANAGE);
        return ApiResponse.ok(experimentService.createExperiment(request));
    }

    @PutMapping("/{experimentKey}")
    @Operation(summary = "Update an experiment", description = "Updates configuration, traffic allocation, or variants of an existing experiment. Requires CONFIG_MANAGE.")
    public ApiResponse<ExperimentDtos.ExperimentDto> updateExperiment(
            @PathVariable String experimentKey,
            @Valid @RequestBody ExperimentDtos.UpdateExperimentRequest request) {
        requirePermission(Permissions.CONFIG_MANAGE);
        return ApiResponse.ok(experimentService.updateExperiment(experimentKey, request));
    }

    @PutMapping("/{experimentKey}/status")
    @Operation(summary = "Change experiment status", description = "Transitions experiment between DRAFT, ACTIVE, PAUSED, and ARCHIVED. Requires CONFIG_MANAGE.")
    public ApiResponse<ExperimentDtos.ExperimentDto> updateStatus(
            @PathVariable String experimentKey,
            @Valid @RequestBody ExperimentDtos.UpdateStatusRequest request) {
        requirePermission(Permissions.CONFIG_MANAGE);
        return ApiResponse.ok(experimentService.updateStatus(experimentKey, request.status()));
    }

    @PostMapping("/{experimentKey}/overrides")
    @Operation(summary = "Add subject overrides", description = "Forces specific subject IDs into a chosen variant. Requires CONFIG_MANAGE.")
    public ApiResponse<ExperimentDtos.ExperimentDto> addOverride(
            @PathVariable String experimentKey,
            @Valid @RequestBody ExperimentDtos.AddOverrideRequest request) {
        requirePermission(Permissions.CONFIG_MANAGE);
        return ApiResponse.ok(experimentService.addOverride(experimentKey, request.variantKey(), request.subjectIds()));
    }

    @DeleteMapping("/{experimentKey}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an experiment", description = "Permanently removes an experiment configuration. Requires CONFIG_MANAGE.")
    public ApiResponse<Void> deleteExperiment(@PathVariable String experimentKey) {
        requirePermission(Permissions.CONFIG_MANAGE);
        experimentService.deleteExperiment(experimentKey);
        return ApiResponse.ok(null);
    }

    @GetMapping("/{experimentKey}/analytics")
    @Operation(summary = "Get experiment analytics", description = "Retrieves impressions, conversions, and conversion rates across all variants. Requires CONFIG_VIEW.")
    public ApiResponse<ExperimentDtos.ExperimentAnalyticsResponse> getAnalytics(
            @PathVariable String experimentKey,
            @RequestParam(required = false) String metricName,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant since) {
        requirePermission(Permissions.CONFIG_VIEW);
        return ApiResponse.ok(experimentService.getAnalytics(experimentKey, metricName, since));
    }

    @PostMapping("/cache/reload")
    @Operation(summary = "Reload cache", description = "Refreshes the in-memory experiment cache from the database. Requires CONFIG_MANAGE.")
    public ApiResponse<Map<String, String>> reloadCache() {
        requirePermission(Permissions.CONFIG_MANAGE);
        experimentService.reloadCache();
        return ApiResponse.ok(Map.of("message", "Experiment cache reloaded successfully"));
    }
}
