package com.costonomy.mp.supplier.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.supplier.service.SupplierStandingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A supplier's standing in the Marketplace. The business itself, its stores and its verification are created and
 * edited in costonomy-common-api (D-208); this answers what the Marketplace makes of them.
 */
@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Suppliers")
public class SupplierController {

    private final SupplierStandingService standings;

    @GetMapping("/{id}/standing")
    @Operation(summary = "Lifecycle, verification and whether the supplier and each store can take orders")
    public ApiResponse<SupplierStandingService.Standing> standing(@PathVariable Long id) {
        return ApiResponse.ok(standings.standing(ActorContext.requireUserId(), id));
    }
}
