package com.costonomy.mp.delivery.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.delivery.web.dto.DeliveryDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A store's own delivery terms. Doc 06 §7.
 *
 * <p>The policy already decided how every order is delivered — no supplier could
 * read or change it. This is the screen behind that, and it is the supplier's
 * own: whether they deliver themselves, what they charge for it, the minimum
 * order they will carry, and how far they will go.
 *
 * <p><b>Absent means the platform default</b>, which is Costonomy delivery only.
 * A store that has never opened this screen has not opted out of anything.
 */
@Service
@RequiredArgsConstructor
public class DeliveryPolicyService {

    private final JdbcTemplate jdbc;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final DeliveryDirectory directory;

    @Transactional(readOnly = true)
    public DeliveryDtos.DeliveryPolicyResponse get(Long actorId, Long storeId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        var policy = directory.deliveryPolicy(storeId);
        return new DeliveryDtos.DeliveryPolicyResponse(
                storeId,
                policy.ownDeliveryEnabled(),
                policy.costonomyDeliveryEnabled(),
                policy.ownDeliveryFee(),
                policy.ownDeliveryMinOrderValue(),
                policy.maxDeliveryRadiusKm());
    }

    @Transactional
    public DeliveryDtos.DeliveryPolicyResponse put(
            Long actorId, Long storeId, DeliveryDtos.UpdateDeliveryPolicyRequest request) {

        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, storeId, "SupplierStore");

        boolean own = Boolean.TRUE.equals(request.ownDeliveryEnabled());
        boolean costonomy = Boolean.TRUE.equals(request.costonomyDeliveryEnabled());

        // Turning both off is not a policy, it is a store nobody can buy from —
        // and it would fail at checkout, long after the decision, with an error
        // about no delivery partner rather than about this setting.
        if (!own && !costonomy) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Choose at least one way to deliver. To stop taking orders, "
                            + "set the store offline instead.");
        }

        var before = directory.deliveryPolicy(storeId);

        jdbc.update("""
                insert into supplier_delivery_policy
                    (supplier_store_id, own_delivery_enabled, costonomy_delivery_enabled,
                     own_delivery_fee, own_delivery_min_order_value, max_delivery_radius_km,
                     created_at, updated_at, version)
                values (?, ?, ?, ?, ?, ?, now(6), now(6), 0)
                on duplicate key update
                    own_delivery_enabled = values(own_delivery_enabled),
                    costonomy_delivery_enabled = values(costonomy_delivery_enabled),
                    own_delivery_fee = values(own_delivery_fee),
                    own_delivery_min_order_value = values(own_delivery_min_order_value),
                    max_delivery_radius_km = values(max_delivery_radius_km),
                    updated_at = now(6), version = version + 1
                """,
                storeId, own, costonomy,
                request.ownDeliveryFee() == null ? BigDecimal.ZERO : request.ownDeliveryFee(),
                request.ownDeliveryMinOrderValue(),
                request.maxDeliveryRadiusKm());

        auditService.recordChange(actorId, "DELIVERY_POLICY_UPDATED", "SUPPLIER_STORE", storeId,
                Map.of("own", before.ownDeliveryEnabled(),
                        "costonomy", before.costonomyDeliveryEnabled()),
                Map.of("own", own, "costonomy", costonomy),
                "Supplier changed their delivery policy");

        return get(actorId, storeId);
    }
}
