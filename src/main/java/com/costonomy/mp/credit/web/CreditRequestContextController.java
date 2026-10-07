package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.credit.service.CreditRequestContextService;
import com.costonomy.mp.credit.web.dto.CreditLifecycleDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The supplier's credit request card: what this store knows about the outlet asking (D-168). */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditRequestContextController {

    private final CreditRequestContextService context;

    @GetMapping("/supplier-stores/{storeId}/credit/requests/{agreementId}/context")
    @Operation(summary = "What this store knows about the restaurant asking for credit",
            description = """
                    Orders in the last 90 India days with THIS store only (count, value, average, cancelled), the first
                    and last order dates, how many of this store's invoices were ever overdue, how an earlier line
                    ended (`pastLineStatus` REJECTED, CLOSED or EXPIRED) and the line's history, newest first. Nothing
                    about the restaurant's dealings with any other supplier is ever included. Needs
                    `CREDIT_REQUEST_VIEW` on the store; another store's user, the restaurant, and an agreement that is
                    not this store's all get a 404.
                    """)
    public ApiResponse<CreditLifecycleDtos.RequestContextResponse> get(
            @PathVariable Long storeId, @PathVariable Long agreementId) {
        return ApiResponse.ok(context.context(ActorContext.requireUserId(), storeId, agreementId));
    }
}
