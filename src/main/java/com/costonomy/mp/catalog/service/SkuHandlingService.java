package com.costonomy.mp.catalog.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SkuHandlingDeclaration;
import com.costonomy.mp.catalog.domain.SupplierSku;
import com.costonomy.mp.catalog.repository.SkuHandlingDeclarationRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How a SKU must be handled, declared by its supplier (D-134). Cold chain gates which carriers may carry it and
 * catch-weight moves money (D-128), so neither is edited in place: a change closes the current declaration and opens
 * the next, with who declared it and why, and an audit row records before and after.
 *
 * <p>The SKU's own flags stay as the one effective value every reader uses; only this service writes them after the
 * SKU exists. Orders already placed carry snapshots of the flags and are unaffected; the next subscription order
 * picks up the new declaration.
 *
 * <p>A product's own cold-chain flag is a floor: a supplier cannot declare a chilled product's SKU as not chilled.
 */
@Service
@RequiredArgsConstructor
public class SkuHandlingService {

    private final SupplierSkuRepository skus;
    private final SkuHandlingDeclarationRepository declarations;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    /** Whether the product itself requires cold chain, which no SKU of it may declare away. */
    public boolean productRequiresColdChain(Long canonicalProductId) {
        if (canonicalProductId == null) {
            return false;
        }
        var list = jdbc.queryForList("select requires_cold_chain from canonical_product where id = ?",
                Boolean.class, canonicalProductId);
        return !list.isEmpty() && Boolean.TRUE.equals(list.get(0));
    }

    /**
     * The first declaration, written when the SKU is created (inside the creating transaction). The flags are the
     * supplier's request, raised to the product's floor; asking for less than the floor is refused.
     */
    @Transactional
    public void declareInitial(SupplierSku sku, Boolean requestedColdChain, boolean catchWeight, Long actorId) {
        boolean floor = productRequiresColdChain(sku.getCanonicalProductId());
        if (floor && Boolean.FALSE.equals(requestedColdChain)) {
            throw floorViolation();
        }
        boolean coldChain = floor || Boolean.TRUE.equals(requestedColdChain);
        sku.setRequiresColdChain(coldChain);
        sku.setCatchWeight(catchWeight);
        skus.save(sku);
        open(sku, coldChain, catchWeight, actorId, "Declared when the SKU was created");
    }

    /**
     * Supersede the SKU's handling declaration.
     *
     * @param coldChain  the new value, or null to leave it as it is
     * @param catchWeight the new value, or null to leave it as it is
     * @return the SKU; unchanged values do nothing at all (no rows, no audit), which is what a rate-sheet or batch
     *         save that passes the current values straight back relies on
     */
    @Transactional
    public SupplierSku declare(Long actorId, Long skuId, Boolean coldChain, Boolean catchWeight, String reason) {
        var found = skus.findById(skuId).orElseThrow(() -> new NotFoundException("SupplierSku", skuId));
        accessControl.requireScoped(actorId, Permissions.CATALOG_EDIT, ScopeType.SUPPLIER_STORE,
                found.getSupplierStoreId(), "SupplierSku");

        // SKU first, then the declaration: the lock order used everywhere.
        var sku = skus.lockById(skuId).orElseThrow(() -> new NotFoundException("SupplierSku", skuId));
        boolean newCold = coldChain == null ? sku.isRequiresColdChain() : coldChain;
        boolean newCatch = catchWeight == null ? sku.isCatchWeight() : catchWeight;
        if (newCold == sku.isRequiresColdChain() && newCatch == sku.isCatchWeight()) {
            return sku;
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Say why the handling is changing; it is kept on record.");
        }
        if (!newCold && productRequiresColdChain(sku.getCanonicalProductId())) {
            throw floorViolation();
        }

        Map<String, Object> before = state(sku.isRequiresColdChain(), sku.isCatchWeight());
        Map<String, Object> after = state(newCold, newCatch);

        var current = declarations.findBySupplierSkuIdAndEffectiveToIsNull(skuId).orElse(null);
        Instant now = Instant.now();
        if (current != null) {
            current.setEffectiveTo(now);
            declarations.saveAndFlush(current);
        }
        var next = open(sku, newCold, newCatch, actorId, reason.trim());
        if (current != null) {
            current.setSupersededById(next.getId());
            declarations.save(current);
        }

        sku.setRequiresColdChain(newCold);
        sku.setCatchWeight(newCatch);
        skus.save(sku);

        // Joins this transaction on purpose: the declaration and its audit row stand or fall together.
        auditService.recordChange(actorId, "SKU_HANDLING_DECLARED", "SUPPLIER_SKU", skuId, before, after,
                reason.trim());
        return sku;
    }

    private SkuHandlingDeclaration open(SupplierSku sku, boolean coldChain, boolean catchWeight, Long actorId,
                                        String reason) {
        var declaration = new SkuHandlingDeclaration();
        declaration.setSupplierSkuId(sku.getId());
        declaration.setRequiresColdChain(coldChain);
        declaration.setCatchWeight(catchWeight);
        declaration.setDeclaredBy(actorId);
        declaration.setReason(reason);
        return declarations.saveAndFlush(declaration);
    }

    private static Map<String, Object> state(boolean coldChain, boolean catchWeight) {
        var state = new LinkedHashMap<String, Object>();
        state.put("requiresColdChain", coldChain);
        state.put("isCatchWeight", catchWeight);
        return state;
    }

    private static BusinessException floorViolation() {
        return new BusinessException(ErrorCode.VALIDATION_ERROR,
                "This product always needs cold chain, so this item can't be declared as not chilled.");
    }
}
