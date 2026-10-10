package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.DeliveryMode;
import com.costonomy.mp.delivery.slot.DeliverySlot;
import com.costonomy.mp.delivery.slot.DeliverySlotRepository;
import com.costonomy.mp.procurement.domain.OrderItemStatus;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.OrderNumberGenerator;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SubscriptionService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    private final SubscriptionRepository subscriptions;
    private final SubscriptionSkipDateRepository skipDates;
    private final DeliverySlotRepository deliverySlots;
    private final com.costonomy.mp.procurement.service.OrderFunding orderFunding;
    private final com.costonomy.mp.delivery.service.DeliveryDirectory deliveryPolicies;
    private final AccessControlService accessControl;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    @Transactional
    public SubscriptionDtos.SubscriptionResponse createSubscription(
            Long actorId, Long outletId, SubscriptionDtos.CreateSubscriptionRequest request) {

        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, outletId, "Outlet");

        LocalDate today = LocalDate.now(ZONE);
        if (request.startDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Subscription start date cannot be in the past");
        }
        if (request.endDate() != null && request.endDate().isBefore(request.startDate())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "End date cannot be before start date");
        }

        // The item must be this supplier's, and its unit is the SKU's own: whatever unit the client sent is ignored.
        var sku = jdbc.query("""
                select canonical_product_id, pack_unit from supplier_sku where id = ? and supplier_store_id = ?
                """,
                rs -> rs.next() ? new Object[] {rs.getObject(1, Long.class), rs.getString(2)} : null,
                request.supplierSkuId(), request.supplierStoreId());
        if (sku == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "SKU does not belong to the selected supplier store");
        }
        Long canonicalProductId = (Long) sku[0];
        String unit = (String) sku[1];

        // Checked here so a subscription that can never be funded or delivered is refused now, not every morning.
        String paymentMethod = request.paymentMethod() == null ? "WALLET" : request.paymentMethod().trim().toUpperCase();
        if (!paymentMethod.equals("WALLET") && !paymentMethod.equals("CREDIT")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Subscriptions can be paid from the wallet or on credit.");
        }
        if (!orderFunding.canFund(paymentMethod, outletId, request.supplierStoreId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "You don't have active credit with this supplier.");
        }
        String deliveryMode = request.deliveryMode() == null ? "SUPPLIER_DELIVERY" : request.deliveryMode().trim().toUpperCase();
        if (!deliveryMode.equals("SUPPLIER_DELIVERY") && !deliveryMode.equals("PICKUP")) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Subscriptions are delivered by the supplier or picked up.");
        }
        if (deliveryMode.equals("SUPPLIER_DELIVERY")
                && !deliveryPolicies.deliveryPolicy(request.supplierStoreId()).ownDeliveryEnabled()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This supplier doesn't deliver. Choose pickup.");
        }
        if (request.preferredSlotId() != null) {
            var slot = deliverySlots.findById(request.preferredSlotId()).orElse(null);
            if (slot == null || !slot.getSupplierStoreId().equals(request.supplierStoreId()) || !slot.isActive()) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That delivery slot isn't available at this supplier.");
            }
        }

        Subscription sub = new Subscription();
        sub.setOutletId(outletId);
        sub.setSupplierStoreId(request.supplierStoreId());
        sub.setCanonicalProductId(canonicalProductId);
        sub.setSupplierSkuId(request.supplierSkuId());
        sub.setQuantity(request.quantity());
        sub.setUnit(unit);
        sub.setFrequency(request.frequency());
        sub.setPreferredSlotId(request.preferredSlotId());
        sub.setDeliveryMode(deliveryMode);
        sub.setPaymentMethod(paymentMethod);
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(request.startDate());
        sub.setEndDate(request.endDate());
        sub.setNotes(request.notes());

        sub.setNextDeliveryDate(nextDue(sub, request.startDate(), Collections.emptySet()));

        subscriptions.save(sub);

        auditService.record(actorId, null, "SUBSCRIPTION_CREATED", "SUBSCRIPTION",
                sub.getId(), null, sub.getStatus().name(), "Outlet " + outletId, "API");

        return toResponse(sub);
    }

    @Transactional(readOnly = true)
    public SubscriptionDtos.SubscriptionResponse getSubscription(Long actorId, Long id) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));

        requireEitherScope(actorId, sub.getOutletId(), sub.getSupplierStoreId());
        return toResponse(sub);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDtos.SubscriptionResponse> listByOutlet(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                ScopeType.OUTLET, outletId, "Outlet");

        return subscriptions.findByOutletIdOrderByCreatedAtDesc(outletId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SubscriptionDtos.SubscriptionResponse> listBySupplierStore(Long actorId, Long supplierStoreId) {
        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        return subscriptions.findBySupplierStoreIdOrderByCreatedAtDesc(supplierStoreId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse pauseSubscription(Long actorId, Long id) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireOutletEdit(actorId, sub);

        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "Cannot pause a cancelled subscription");
        }

        sub.setStatus(SubscriptionStatus.PAUSED);
        subscriptions.save(sub);

        auditService.record(actorId, null, "SUBSCRIPTION_PAUSED", "SUBSCRIPTION",
                sub.getId(), "ACTIVE", "PAUSED", null, "API");

        return toResponse(sub);
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse resumeSubscription(Long actorId, Long id) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireOutletEdit(actorId, sub);

        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "Cannot resume a cancelled subscription");
        }

        sub.setStatus(SubscriptionStatus.ACTIVE);
        LocalDate today = LocalDate.now(ZONE);
        Set<LocalDate> skipped = skipDates.findBySubscriptionId(id).stream()
                .map(SubscriptionSkipDate::getSkipDate)
                .collect(Collectors.toSet());

        sub.setNextDeliveryDate(nextDue(sub, today, skipped));

        subscriptions.save(sub);

        auditService.record(actorId, null, "SUBSCRIPTION_RESUMED", "SUBSCRIPTION",
                sub.getId(), "PAUSED", "ACTIVE", null, "API");

        return toResponse(sub);
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse cancelSubscription(Long actorId, Long id, String reason) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireOutletEdit(actorId, sub);

        sub.setStatus(SubscriptionStatus.CANCELLED);
        sub.setNextDeliveryDate(null);
        subscriptions.save(sub);

        auditService.record(actorId, null, "SUBSCRIPTION_CANCELLED", "SUBSCRIPTION",
                sub.getId(), null, "CANCELLED", reason, "API");

        return toResponse(sub);
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse addSkipDate(
            Long actorId, Long id, SubscriptionDtos.AddSkipDateRequest request) {

        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireOutletEdit(actorId, sub);

        LocalDate today = LocalDate.now(ZONE);
        if (request.skipDate().isBefore(today)) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Cannot skip dates in the past");
        }

        if (!skipDates.existsBySubscriptionIdAndSkipDate(id, request.skipDate())) {
            SubscriptionSkipDate skip = new SubscriptionSkipDate();
            skip.setSubscriptionId(id);
            skip.setSkipDate(request.skipDate());
            skip.setReason(request.reason());
            skipDates.save(skip);
        }

        if (request.skipDate().equals(sub.getNextDeliveryDate())) {
            Set<LocalDate> allSkipped = skipDates.findBySubscriptionId(id).stream()
                    .map(SubscriptionSkipDate::getSkipDate)
                    .collect(Collectors.toSet());
            allSkipped.add(request.skipDate());

            sub.setNextDeliveryDate(nextDue(sub, sub.getNextDeliveryDate(), allSkipped));
            subscriptions.save(sub);
        }

        return toResponse(sub);
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse removeSkipDate(Long actorId, Long id, LocalDate skipDate) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireOutletEdit(actorId, sub);

        skipDates.findBySubscriptionIdAndSkipDate(id, skipDate).ifPresent(skipDates::delete);

        // Recalculate next delivery date
        LocalDate today = LocalDate.now(ZONE);
        Set<LocalDate> allSkipped = skipDates.findBySubscriptionId(id).stream()
                .map(SubscriptionSkipDate::getSkipDate)
                .collect(Collectors.toSet());

        sub.setNextDeliveryDate(nextDue(sub, today, allSkipped));
        subscriptions.save(sub);

        return toResponse(sub);
    }

    @Transactional(readOnly = true)
    public SubscriptionDtos.SubscriptionManifestResponse getManifest(
            Long actorId, Long supplierStoreId, LocalDate targetDate) {

        accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        LocalDate date = (targetDate != null) ? targetDate : LocalDate.now(ZONE);

        List<Subscription> active = subscriptions.findBySupplierStoreIdAndStatus(
                supplierStoreId, SubscriptionStatus.ACTIVE);

        List<Subscription> dueSubscriptions = active.stream()
                .filter(sub -> isDueOn(sub, date))
                .toList();

        // Build item summaries
        Map<Long, BigDecimal> skuTotals = new HashMap<>();
        Map<Long, String> skuUnits = new HashMap<>();
        Map<Long, Long> skuProducts = new HashMap<>();

        for (Subscription sub : dueSubscriptions) {
            skuTotals.merge(sub.getSupplierSkuId(), sub.getQuantity(), BigDecimal::add);
            skuUnits.putIfAbsent(sub.getSupplierSkuId(), sub.getUnit());
            skuProducts.putIfAbsent(sub.getSupplierSkuId(), sub.getCanonicalProductId());
        }

        Map<Long, String> productNames = loadProductNames(new ArrayList<>(skuProducts.values()));

        List<SubscriptionDtos.ManifestItemSummary> summaries = skuTotals.entrySet().stream()
                .map(e -> {
                    Long skuId = e.getKey();
                    Long prodId = skuProducts.get(skuId);
                    return new SubscriptionDtos.ManifestItemSummary(
                            skuId,
                            prodId,
                            productNames.getOrDefault(prodId, "SKU #" + skuId),
                            e.getValue(),
                            skuUnits.get(skuId)
                    );
                })
                .toList();

        // Build delivery list
        Map<Long, DeliverySlot> slotMap = deliverySlots.findBySupplierStoreId(supplierStoreId).stream()
                .collect(Collectors.toMap(DeliverySlot::getId, s -> s));

        List<SubscriptionDtos.ManifestDeliveryOrder> deliveries = dueSubscriptions.stream()
                .map(sub -> {
                    DeliverySlot slot = sub.getPreferredSlotId() != null ? slotMap.get(sub.getPreferredSlotId()) : null;
                    String slotName = slot != null ? slot.getSlotName() : "Standard";
                    OutletInfo outlet = loadOutletInfo(sub.getOutletId());

                    return new SubscriptionDtos.ManifestDeliveryOrder(
                            sub.getId(),
                            sub.getOutletId(),
                            outlet.name(),
                            outlet.restaurantName(),
                            outlet.address(),
                            outlet.phone(),
                            sub.getPreferredSlotId(),
                            slotName,
                            sub.getSupplierSkuId(),
                            productNames.getOrDefault(sub.getCanonicalProductId(), "Item"),
                            sub.getQuantity(),
                            sub.getUnit(),
                            sub.getDeliveryMode()
                    );
                })
                .toList();

        return new SubscriptionDtos.SubscriptionManifestResponse(date, supplierStoreId, summaries, deliveries);
    }

    private boolean isDueOn(Subscription sub, LocalDate date) {
        return SubscriptionSchedule.isDue(sub.getStartDate(), sub.getEndDate(), sub.getFrequency(), date,
                skippedDates(sub.getId()));
    }

    private Set<LocalDate> skippedDates(Long subscriptionId) {
        return skipDates.findBySubscriptionId(subscriptionId).stream()
                .map(SubscriptionSkipDate::getSkipDate)
                .collect(Collectors.toSet());
    }

    /** The next due date from a day, or null when the subscription has no more deliveries. */
    private LocalDate nextDue(Subscription sub, LocalDate from, Set<LocalDate> skipped) {
        return SubscriptionSchedule.nextOnOrAfter(sub.getStartDate(), sub.getEndDate(), sub.getFrequency(),
                from, skipped);
    }

    /**
     * Changing a subscription is the restaurant's call: it needs the right to create orders for the outlet.
     * Seeing one (a supplier's staff, or an outlet member who can only view) is not enough. Refused as not
     * found, like any other thing the actor has no business with.
     */
    private void requireOutletEdit(Long actorId, Subscription sub) {
        accessControl.requireScoped(actorId, Permissions.PROCUREMENT_CREATE,
                ScopeType.OUTLET, sub.getOutletId(), "Outlet");
    }

    private void requireEitherScope(Long actorId, Long outletId, Long supplierStoreId) {
        try {
            accessControl.requireScoped(actorId, Permissions.OUTLET_VIEW,
                    ScopeType.OUTLET, outletId, "Outlet");
        } catch (Exception e) {
            accessControl.requireScoped(actorId, Permissions.STORE_VIEW,
                    ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");
        }
    }

    private SubscriptionDtos.SubscriptionResponse toResponse(Subscription sub) {
        List<LocalDate> skips = skipDates.findBySubscriptionId(sub.getId()).stream()
                .map(SubscriptionSkipDate::getSkipDate)
                .sorted()
                .toList();

        String outletName = jdbc.queryForObject(
                "select name from outlet where id = ?", String.class, sub.getOutletId());
        String storeName = jdbc.queryForObject(
                "select name from supplier_store where id = ?", String.class, sub.getSupplierStoreId());

        String prodName = null;
        String prodImage = null;
        if (sub.getCanonicalProductId() != null) {
            var row = jdbc.query("select name, image_url from canonical_product where id = ?",
                    rs -> rs.next() ? Map.of("name", rs.getString(1), "image", rs.getString(2) == null ? "" : rs.getString(2)) : null,
                    sub.getCanonicalProductId());
            if (row != null) {
                prodName = row.get("name");
                prodImage = row.get("image");
            }
        }

        String slotName = null;
        if (sub.getPreferredSlotId() != null) {
            slotName = deliverySlots.findById(sub.getPreferredSlotId())
                    .map(DeliverySlot::getSlotName)
                    .orElse(null);
        }

        return new SubscriptionDtos.SubscriptionResponse(
                sub.getId(),
                sub.getOutletId(),
                outletName,
                sub.getSupplierStoreId(),
                storeName,
                sub.getCanonicalProductId(),
                prodName,
                prodImage,
                sub.getSupplierSkuId(),
                prodName != null ? prodName : ("SKU #" + sub.getSupplierSkuId()),
                sub.getQuantity(),
                sub.getUnit(),
                sub.getFrequency(),
                sub.getPreferredSlotId(),
                slotName,
                sub.getDeliveryMode(),
                sub.getPaymentMethod() != null ? sub.getPaymentMethod() : "WALLET",
                sub.getStatus(),
                sub.getStartDate(),
                sub.getEndDate(),
                sub.getNextDeliveryDate(),
                skips,
                sub.getNotes()
        );
    }

    private Map<Long, String> loadProductNames(List<Long> productIds) {
        if (productIds.isEmpty()) return Collections.emptyMap();
        Map<Long, String> map = new HashMap<>();
        String inSql = String.join(",", Collections.nCopies(productIds.size(), "?"));
        jdbc.query("select id, name from canonical_product where id in (" + inSql + ")",
                rs -> {
                    map.put(rs.getLong(1), rs.getString(2));
                },
                productIds.toArray());
        return map;
    }

    private record OutletInfo(String name, String restaurantName, String address, String phone) {}

    private OutletInfo loadOutletInfo(Long outletId) {
        var row = jdbc.query("""
                select o.name, r.name, concat_ws(', ', o.address_line1, o.landmark, o.city), o.contact_phone
                  from outlet o
                  join restaurant r on r.id = o.restaurant_id
                 where o.id = ?
                """,
                rs -> rs.next() ? new OutletInfo(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)) : null,
                outletId);
        return row != null ? row : new OutletInfo("Outlet #" + outletId, "Restaurant", "Address", "");
    }
}
