package com.costonomy.mp.procurement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.procurement.service.RequirementService;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Requirements — what an outlet needs. Doc 04 §9. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Requirements")
public class RequirementController {

    private final RequirementService requirements;

    @PostMapping("/outlets/{outletId}/requirements")
    @Operation(
            summary = "Raise a requirement",
            description = """
                    A requirement is not consumed by being ordered. Quantities are
                    credited against it only when a supplier actually accepts, so a
                    rejection, timeout or partial acceptance leaves the shortfall
                    sitting here — still sourceable, with nothing to retype.
                    """)
    public ApiResponse<ProcurementDtos.RequirementResponse> create(
            @PathVariable Long outletId,
            @Valid @RequestBody ProcurementDtos.CreateRequirementRequest request) {
        return ApiResponse.ok(requirements.create(ActorContext.requireUserId(), outletId, request));
    }

    @GetMapping("/outlets/{outletId}/requirements")
    @Operation(
            summary = "An outlet's requirements",
            description = "`openOnly` returns those still needing something — open, "
                    + "sourcing or partially fulfilled.")
    public ApiResponse<List<ProcurementDtos.RequirementResponse>> list(
            @PathVariable Long outletId,
            @RequestParam(defaultValue = "false") boolean openOnly) {
        return ApiResponse.ok(
                requirements.listForOutlet(ActorContext.requireUserId(), outletId, openOnly));
    }

    @GetMapping("/requirements/{id}")
    @Operation(
            summary = "Get a requirement",
            description = "Each item carries requested, fulfilled and remaining "
                    + "quantities separately (§23A.14).")
    public ApiResponse<ProcurementDtos.RequirementResponse> get(@PathVariable Long id) {
        return ApiResponse.ok(requirements.get(ActorContext.requireUserId(), id));
    }

    @PostMapping("/requirements/{id}/cancel")
    @Operation(summary = "Cancel a requirement")
    public ApiResponse<ProcurementDtos.RequirementResponse> cancel(@PathVariable Long id) {
        return ApiResponse.ok(requirements.cancel(ActorContext.requireUserId(), id));
    }
}
