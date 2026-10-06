package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.credit.service.CreditLifecycleService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditLifecycleDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * What a supplier does to a line or an invoice beyond the negotiation: close the line, and give an invoice longer to
 * be paid. Their own controller, so the credit controller stays the negotiation (D-136, D-138).
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditSupplierLifecycleController {

    private final CreditLifecycleService lifecycle;

    @PostMapping("/credit/agreements/{id}/close")
    @Operation(summary = "Close a credit line",
            description = """
                    `ACTIVE` or `SUSPENDED` to `CLOSED`, with a reason. Refused with 409 `INVALID_STATE_TRANSITION`
                    (details: `owed`, `reserved`) while anything is owed on the line or held for an order in flight:
                    suspend it instead, and close it once it is paid. A request or an offer is declined or
                    withdrawn, not closed (409). A closed line takes no orders; the restaurant is told and may ask
                    again. Closing again answers 200 and changes nothing. Needs `CREDIT_MODIFY` on the store;
                    anyone else gets a 404.
                    """)
    public ApiResponse<CreditDtos.AgreementResponse> close(
            @PathVariable Long id, @Valid @RequestBody CreditLifecycleDtos.CloseRequest body) {
        return ApiResponse.ok(lifecycle.close(ActorContext.requireUserId(), id, body.reason()));
    }
}
