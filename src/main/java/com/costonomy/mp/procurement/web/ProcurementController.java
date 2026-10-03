package com.costonomy.mp.procurement.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.identity.security.ActorContext;
import com.costonomy.mp.procurement.service.ProcurementSubmissionService;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Reading orders. D-091.
 *
 * <p><b>The cart is gone.</b> This used to be cart, checkout, approval and
 * submission (doc 04 §10). D-088 made the request the basket and D-091 removed
 * the order acceptance that the cart path depended on — an order created from a
 * cart would arrive at a supplier who had never agreed to it, and under the new
 * lifecycle would arrive {@code CONFIRMED}, which is worse than the old bug.
 *
 * <p>Orders are created by {@code IntentOrderCreator}, from a request the
 * supplier has already accepted and the restaurant has already paid for. What is
 * left here is reading them back.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "Orders")
public class ProcurementController {

    private final ProcurementSubmissionService submission;

    @GetMapping("/outlets/{outletId}/supplier-orders")
    @Operation(summary = "An outlet's orders")
    public ApiResponse<List<ProcurementDtos.SupplierOrderResponse>> outletOrders(
            @PathVariable Long outletId) {
        return ApiResponse.ok(submission.ordersForOutlet(ActorContext.requireUserId(), outletId));
    }

    @GetMapping("/supplier-orders/{id}")
    @Operation(
            summary = "Get a supplier order",
            description = "Visible to the restaurant that placed it and the supplier "
                    + "that received it, and to nobody else.")
    public ApiResponse<ProcurementDtos.SupplierOrderResponse> order(@PathVariable Long id) {
        return ApiResponse.ok(submission.getOrder(ActorContext.requireUserId(), id));
    }
}
