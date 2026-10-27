package com.costonomy.mp.supplier.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.outbox.OutboxService;
import com.costonomy.mp.identity.common.Directory;
import com.costonomy.mp.supplier.domain.SupplierLifecycleStatus;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.client.CommonApiClient;
import com.costonomy.security.client.CommonApiClient.AppOrg;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Where a supplier stands in the Marketplace (D-208). The business, its stores, GSTIN and verification are
 * costonomy-common-api's, edited there by the app; Marketplace reads the lifecycle from them
 * ({@link SupplierStanding}) and owns one decision: activating a verified supplier, which sets the business's
 * Marketplace enrollment live in common.
 */
@Service
@RequiredArgsConstructor
public class SupplierStandingService {

    private final Directory directory;
    private final CommonApiClient common;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final OutboxService outbox;

    public record StoreStanding(Long id, String name, String status, boolean takesOrders) {
    }

    /** {@code canTrade}: the supplier may receive orders (ACTIVE). */
    public record Standing(Long supplierId, String lifecycleStatus, String verificationStatus, boolean canTrade,
                           List<StoreStanding> stores) {
    }

    public Standing standing(Long actorId, Long supplierId) {
        accessControl.requireScoped(actorId, Permissions.SUPPLIER_VIEW, ScopeType.SUPPLIER, supplierId, "Supplier");
        return standingOf(supplierId);
    }

    /** Activate a verified supplier so it can receive orders. Requires {@code SUPPLIER_VERIFY}. */
    @Transactional
    public Standing activate(Long actorId, Long supplierId) {
        accessControl.require(actorId, Permissions.SUPPLIER_VERIFY, ScopeType.PLATFORM, null);
        AppOrg org = supplier(supplierId);
        SupplierLifecycleStatus from = SupplierStanding.of(org);
        if (!from.canTransitionTo(SupplierLifecycleStatus.ACTIVE)) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                    "Cannot move supplier from " + from + " to ACTIVE");
        }
        common.setEnrollment(supplierId, "ACTIVE", true, actorId);
        directory.evictOrg(supplierId);
        auditService.record(actorId, null, "SUPPLIER_LIFECYCLE_CHANGED", "SUPPLIER", supplierId, from.name(),
                SupplierLifecycleStatus.ACTIVE.name(), null, "API");
        outbox.publish("SupplierActivated", "SUPPLIER", supplierId, Map.of("supplierId", supplierId), actorId);
        return standingOf(supplierId);
    }

    private Standing standingOf(Long supplierId) {
        AppOrg org = supplier(supplierId);
        List<StoreStanding> stores = directory.outletsOf(supplierId).stream()
                .filter(s -> "STORE".equals(s.kind()))
                .map(s -> new StoreStanding(s.id(), s.name(), SupplierStanding.storeStatus(s),
                        SupplierStanding.takesOrders(s)))
                .toList();
        SupplierLifecycleStatus lifecycle = SupplierStanding.of(org);
        return new Standing(supplierId, lifecycle.name(),
                SupplierStanding.verification(org.verificationStatus()).name(),
                lifecycle == SupplierLifecycleStatus.ACTIVE, stores);
    }

    private AppOrg supplier(Long supplierId) {
        return directory.org(supplierId).filter(o -> "SUPPLIER".equals(o.orgType()))
                .orElseThrow(() -> new NotFoundException("Supplier", supplierId));
    }
}
