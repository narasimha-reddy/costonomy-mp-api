package com.costonomy.mp.delivery.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.admin.service.AdminQueryService;
import com.costonomy.mp.delivery.service.DeliverySimulationService;
import com.costonomy.mp.admin.web.dto.AdminDtos;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Drives a mock delivery forward. Doc 06 §11.
 *
 * <p>Protected, and unavailable to normal users — the checks live in
 * {@link DeliverySimulationService} alongside the work they guard.
 */
@RestController
@RequestMapping("/api/v1/internal/deliveries/{id}/simulate")
@RequiredArgsConstructor
@Tag(name = "Delivery")
public class DeliverySimulationController {

    private final DeliverySimulationService simulation;
    private final AdminQueryService adminQueries;

    @PostMapping
    @Operation(
            summary = "Simulate a delivery partner's event (internal, mock providers only)",
            description = """
                    Moves a mock delivery to the named status, optionally reporting a driver
                    position or a revised ETA. Every failure doc 06 §11 lists is reachable
                    this way — driver cancellation, pickup failure, delivery failure — so a
                    failure path is exercised by asking for it rather than by mocking.

                    Requires DELIVERY_OPERATE at platform scope, which no tenant role holds.
                    """)
    public ApiResponse<AdminDtos.DeliveryDetail> simulate(
            @PathVariable Long id,
            @Valid @RequestBody DeliveryDtos.SimulateEventRequest body) {

        Long actorId = ActorContext.requireUserId();
        simulation.simulate(actorId, id, body);

        // The operations view, not the restaurant's. An operator holds no tenant
        // permission since V17, and reading back through the tenant endpoint would
        // have been the very shortcut that change exists to remove.
        return ApiResponse.ok(adminQueries.delivery(actorId, simulation.orderIdOf(id)));
    }
}
