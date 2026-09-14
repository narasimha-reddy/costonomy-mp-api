package com.costonomy.mp.credit.web;

import com.costonomy.mp.common.api.ApiResponse;
import com.costonomy.mp.credit.service.CreditPolicyService;
import com.costonomy.mp.credit.web.dto.CreditPolicyDtos;
import com.costonomy.mp.identity.security.ActorContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** A store's standing credit offer. Doc 01 §18. */
@RestController
@RequestMapping("/api/v1/supplier-stores/{storeId}/credit-policy")
@RequiredArgsConstructor
@Tag(name = "Credit")
public class CreditPolicyController {

    private final CreditPolicyService policies;

    @GetMapping
    @Operation(summary = "This store's credit policy")
    public ApiResponse<CreditPolicyDtos.PolicyResponse> get(@PathVariable Long storeId) {
        return ApiResponse.ok(policies.get(ActorContext.requireUserId(), storeId));
    }

    @PutMapping
    @Operation(summary = "Set this store's credit policy",
            description = """
                    Decides whether restaurants may ask for credit here, and seeds the terms
                    shown when answering. Turning credit off stops new requests; agreements
                    already granted keep working, because they are commitments already made.
                    """)
    public ApiResponse<CreditPolicyDtos.PolicyResponse> update(
            @PathVariable Long storeId,
            @Valid @RequestBody CreditPolicyDtos.UpdatePolicyRequest body) {
        return ApiResponse.ok(policies.update(ActorContext.requireUserId(), storeId, body));
    }
}
