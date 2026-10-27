package com.costonomy.mp.notification.service;

import com.costonomy.mp.identity.common.Directory;
import com.costonomy.security.client.CommonApiClient;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Who to tell.
 *
 * <p>Derived from live grants, the same way {@code RealtimeEntitlements} derives
 * channels — and expanded down the scope hierarchy for the same reason: a grant on
 * a restaurant covers its outlets, because that is what the grant means everywhere
 * else. Two notions of "who belongs to this outlet" would drift, and the drift
 * would show up as somebody not being told their order was rejected.
 *
 * <p><b>Notifications go to people, not to scopes.</b> Everyone with a grant is
 * told, not just whoever happened to place the order: an order rejected at 6am is
 * the shift manager's problem whether or not they were the one who ordered it.
 */
@Service
@RequiredArgsConstructor
public class NotificationAudience {

    private final CommonApiClient common;
    private final Directory directory;

    /** Everyone who can act for this outlet: its team in costonomy-common-api, business-wide members included. */
    @Transactional(readOnly = true)
    public List<Long> forOutlet(Long outletId) {
        return members(orgOf(outletId), outletId, null);
    }

    /** Everyone who can act for this supplier store. */
    @Transactional(readOnly = true)
    public List<Long> forSupplierStore(Long supplierStoreId) {
        return members(orgOf(supplierStoreId), supplierStoreId, null);
    }

    /**
     * Everyone who can act for this supplier store AND holds {@code permission} there (D-173): a salesperson who may
     * not see credit is not sent the credit digest.
     */
    @Transactional(readOnly = true)
    public List<Long> forSupplierStoreWith(Long supplierStoreId, String permission) {
        return members(orgOf(supplierStoreId), supplierStoreId, permission);
    }

    /** The business an outlet or store belongs to, from common (D-208); null when common does not know it. */
    private Long orgOf(Long outletOrStoreId) {
        return directory.outlet(outletOrStoreId).map(AppOutlet::orgId).orElse(null);
    }

    private List<Long> members(Long orgId, Long outletId, String permission) {
        if (orgId == null) {
            return List.of();
        }
        return common.members(orgId, outletId, permission, null).stream()
                .map(CommonApiClient.CommonMember::userId).distinct().toList();
    }

    /**
     * A push target. {@code id} is common's device id (D-208): the app registers its install with common at
     * sign-in, and Marketplace keeps no device rows.
     */
    public record Device(Long id, String pushToken) {
    }

    /** A user's push targets in Marketplace, as common lists them. */
    public List<Device> devicesOf(Long userId) {
        return devicesOf(List.of(userId)).getOrDefault(userId, List.of());
    }

    /** Push targets of several users in one call to common; a user with none is left out. */
    public Map<Long, List<Device>> devicesOf(Collection<Long> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<Device>> byUser = new HashMap<>();
        for (CommonApiClient.PushDevice d : common.pushDevices(userIds)) {
            if (d.pushToken() != null && !d.pushToken().isBlank()) {
                byUser.computeIfAbsent(d.userId(), k -> new ArrayList<>()).add(new Device(d.deviceId(), d.pushToken()));
            }
        }
        return byUser;
    }

    /** The user's primary phone in common, or null. */
    public String phoneOf(Long userId) {
        return directory.user(userId).map(CommonApiClient.CommonUser::primaryPhone).orElse(null);
    }

}
