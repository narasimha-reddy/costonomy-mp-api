package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.credit.domain.SupplierCreditPolicy;
import com.costonomy.mp.credit.repository.SupplierCreditPolicyRepository;
import com.costonomy.mp.credit.web.dto.CreditPolicyDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A supplier store's standing credit offer. Doc 01 §18.
 *
 * <p>Nothing here grants credit to anybody. It decides whether a restaurant may
 * ask, and what the supplier is offered as a starting point when they answer —
 * every actual limit is still theirs to set on the agreement.
 */
@Service
@RequiredArgsConstructor
public class CreditPolicyService {

    private final SupplierCreditPolicyRepository policies;
    private final AccessControlService accessControl;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public CreditPolicyDtos.PolicyResponse get(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");
        return toResponse(storeId, policies.findBySupplierStoreId(storeId).orElse(null));
    }

    @Transactional
    public CreditPolicyDtos.PolicyResponse update(Long actorId, Long storeId,
                                                  CreditPolicyDtos.UpdatePolicyRequest body) {

        accessControl.requireScoped(actorId, Permissions.CREDIT_MODIFY,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var policy = policies.findBySupplierStoreId(storeId).orElseGet(() -> {
            var fresh = new SupplierCreditPolicy();
            fresh.setSupplierStoreId(storeId);
            return fresh;
        });

        boolean was = Boolean.TRUE.equals(policy.getCreditEnabled());

        policy.setCreditEnabled(body.creditEnabled());
        policy.setDefaultCreditLimit(body.defaultCreditLimit());
        policy.setDefaultCreditPeriodDays(body.defaultCreditPeriodDays());
        policy.setDefaultGracePeriodDays(body.defaultGracePeriodDays() == null
                ? 0 : body.defaultGracePeriodDays());
        policy.setMaxSingleOrderCredit(body.maxSingleOrderCredit());
        policy.setMaxOverdueAmount(body.maxOverdueAmount());
        policy.setAutoSuspendEnabled(body.autoSuspendEnabled() == null || body.autoSuspendEnabled());
        policies.save(policy);

        // Turning credit off is a commercial decision worth being able to point at
        // later — existing agreements keep working, so the only visible effect is
        // that new requests stop arriving.
        auditService.record(actorId, null, "CREDIT_POLICY_UPDATED", "SUPPLIER_STORE",
                storeId, String.valueOf(was), String.valueOf(body.creditEnabled()),
                "Credit policy updated", "API");

        return toResponse(storeId, policy);
    }

    private CreditPolicyDtos.PolicyResponse toResponse(Long storeId, SupplierCreditPolicy policy) {
        if (policy == null) {
            return new CreditPolicyDtos.PolicyResponse(
                    storeId, false, null, null, 0, null, null, true);
        }
        return new CreditPolicyDtos.PolicyResponse(
                storeId, policy.getCreditEnabled(), policy.getDefaultCreditLimit(),
                policy.getDefaultCreditPeriodDays(), policy.getDefaultGracePeriodDays(),
                policy.getMaxSingleOrderCredit(), policy.getMaxOverdueAmount(),
                policy.getAutoSuspendEnabled());
    }
}
