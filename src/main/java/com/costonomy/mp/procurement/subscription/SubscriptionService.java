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
import java.time.DayOfWeek;
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
    private final SupplierOrderRepository supplierOrders;
    private final SupplierOrderItemRepository supplierOrderItems;
    private final SupplierOfferRepository supplierOffers;
    private final DeliverySlotRepository deliverySlots;
    private final OrderNumberGenerator orderNumbers;
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

        // Verify SKU exists and is associated with the store
        Long canonicalProductId = jdbc.query("""
                select canonical_product_id from supplier_sku where id = ? and supplier_store_id = ?
                """,
                rs -> rs.next() ? rs.getLong(1) : null,
                request.supplierSkuId(), request.supplierStoreId());

        if (canonicalProductId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "SKU does not belong to the selected supplier store");
        }

        Subscription sub = new Subscription();
        sub.setOutletId(outletId);
        sub.setSupplierStoreId(request.supplierStoreId());
        sub.setCanonicalProductId(canonicalProductId);
        sub.setSupplierSkuId(request.supplierSkuId());
        sub.setQuantity(request.quantity());
        sub.setUnit(request.unit());
        sub.setFrequency(request.frequency());
        sub.setPreferredSlotId(request.preferredSlotId());
        sub.setDeliveryMode(request.deliveryMode() == null ? "SUPPLIER_DELIVERY" : request.deliveryMode());
        sub.setStatus(SubscriptionStatus.ACTIVE);
        sub.setStartDate(request.startDate());
        sub.setEndDate(request.endDate());
        sub.setNotes(request.notes());

        LocalDate initialNext = calculateNextDeliveryDate(request.startDate(), request.frequency(), Collections.emptySet());
        sub.setNextDeliveryDate(initialNext);

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
        requireEitherScope(actorId, sub.getOutletId(), sub.getSupplierStoreId());

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
        requireEitherScope(actorId, sub.getOutletId(), sub.getSupplierStoreId());

        if (sub.getStatus() == SubscriptionStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION, "Cannot resume a cancelled subscription");
        }

        sub.setStatus(SubscriptionStatus.ACTIVE);
        LocalDate today = LocalDate.now(ZONE);
        Set<LocalDate> skipped = skipDates.findBySubscriptionId(id).stream()
                .map(SubscriptionSkipDate::getSkipDate)
                .collect(Collectors.toSet());

        LocalDate startFrom = sub.getStartDate().isAfter(today) ? sub.getStartDate() : today;
        sub.setNextDeliveryDate(calculateNextDeliveryDate(startFrom, sub.getFrequency(), skipped));

        subscriptions.save(sub);

        auditService.record(actorId, null, "SUBSCRIPTION_RESUMED", "SUBSCRIPTION",
                sub.getId(), "PAUSED", "ACTIVE", null, "API");

        return toResponse(sub);
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse cancelSubscription(Long actorId, Long id, String reason) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireEitherScope(actorId, sub.getOutletId(), sub.getSupplierStoreId());

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
        requireEitherScope(actorId, sub.getOutletId(), sub.getSupplierStoreId());

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

            LocalDate next = advanceFrequencyDate(sub.getNextDeliveryDate(), sub.getFrequency());
            sub.setNextDeliveryDate(calculateNextDeliveryDate(next, sub.getFrequency(), allSkipped));
            subscriptions.save(sub);
        }

        return toResponse(sub);
    }

    @Transactional
    public SubscriptionDtos.SubscriptionResponse removeSkipDate(Long actorId, Long id, LocalDate skipDate) {
        Subscription sub = subscriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Subscription", id));
        requireEitherScope(actorId, sub.getOutletId(), sub.getSupplierStoreId());

        skipDates.findBySubscriptionIdAndSkipDate(id, skipDate).ifPresent(skipDates::delete);

        // Recalculate next delivery date
        LocalDate today = LocalDate.now(ZONE);
        Set<LocalDate> allSkipped = skipDates.findBySubscriptionId(id).stream()
                .map(SubscriptionSkipDate::getSkipDate)
                .collect(Collectors.toSet());

        LocalDate startFrom = sub.getStartDate().isAfter(today) ? sub.getStartDate() : today;
        sub.setNextDeliveryDate(calculateNextDeliveryDate(startFrom, sub.getFrequency(), allSkipped));
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

    @Transactional
    public SubscriptionDtos.GenerateOrdersResponse generateDailyOrders(
            Long actorId, Long supplierStoreId, LocalDate targetDate) {

        accessControl.requireScoped(actorId, Permissions.STORE_EDIT,
                ScopeType.SUPPLIER_STORE, supplierStoreId, "SupplierStore");

        LocalDate date = (targetDate != null) ? targetDate : LocalDate.now(ZONE);

        List<Subscription> active = subscriptions.findBySupplierStoreIdAndStatus(
                supplierStoreId, SubscriptionStatus.ACTIVE);

        List<Subscription> dueSubscriptions = active.stream()
                .filter(sub -> isDueOn(sub, date))
                .toList();

        List<Long> generatedOrderIds = new ArrayList<>();

        for (Subscription sub : dueSubscriptions) {
            // Check if order already exists for this subscription on this date
            Integer existing = jdbc.queryForObject("""
                    select count(*) from supplier_order
                     where subscription_id = ? and scheduled_delivery_date = ?
                       and status not in ('CANCELLED', 'REJECTED')
                    """, Integer.class, sub.getId(), java.sql.Date.valueOf(date));

            if (existing != null && existing > 0) {
                continue;
            }

            // Find current active offer price
            SupplierOffer offer = supplierOffers.findBySupplierSkuIdAndStatus(sub.getSupplierSkuId(), "ACTIVE")
                    .orElse(null);

            BigDecimal unitPrice = (offer != null && offer.getSellingPrice() != null)
                    ? offer.getSellingPrice() : BigDecimal.ZERO;
            BigDecimal gstRate = (offer != null && offer.getGstRate() != null)
                    ? offer.getGstRate() : BigDecimal.ZERO;

            BigDecimal lineValue = Pricing.lineItemValue(unitPrice, sub.getQuantity());
            BigDecimal lineGst = Pricing.lineGst(lineValue, gstRate);
            BigDecimal totalAmount = Pricing.lineTotal(lineValue, lineGst);

            SupplierOrder order = new SupplierOrder();
            order.setSupplierStoreId(supplierStoreId);
            order.setOutletId(sub.getOutletId());
            order.setOrderNumber(orderNumbers.next());
            order.setStatus(SupplierOrderStatus.CONFIRMED);
            order.setPaymentMethod("CREDIT");
            order.setPaymentStatus("PENDING");
            order.setSubtotal(lineValue);
            order.setGstAmount(lineGst);
            order.setTotalAmount(totalAmount);
            order.setAcceptedAmount(totalAmount);

            com.costonomy.mp.procurement.domain.DeliveryMode mode = parseProcurementMode(sub.getDeliveryMode());
            order.setDeliveryMode(mode);
            order.setDeliveryFee(BigDecimal.ZERO);
            order.setDeliverySlotId(sub.getPreferredSlotId());
            order.setScheduledDeliveryDate(date);
            order.setSubscriptionOrder(true);
            order.setSubscriptionId(sub.getId());

            supplierOrders.save(order);

            SupplierOrderItem item = new SupplierOrderItem();
            item.setSupplierOrderId(order.getId());
            item.setCanonicalProductId(sub.getCanonicalProductId());
            item.setSupplierSkuId(sub.getSupplierSkuId());
            item.setRequestedQuantity(sub.getQuantity());
            item.setAcceptedQuantity(sub.getQuantity());
            item.setUnit(sub.getUnit());
            item.setUnitPriceSnapshot(unitPrice);
            item.setGstRateSnapshot(gstRate);
            item.setLineItemValue(lineValue);
            item.setLineGst(lineGst);
            item.setLineTotal(totalAmount);
            item.setStatus(OrderItemStatus.ACCEPTED);
            supplierOrderItems.save(item);

            generatedOrderIds.add(order.getId());

            // Advance subscription's next delivery date
            Set<LocalDate> skipped = skipDates.findBySubscriptionId(sub.getId()).stream()
                    .map(SubscriptionSkipDate::getSkipDate)
                    .collect(Collectors.toSet());
            LocalDate nextDay = advanceFrequencyDate(date, sub.getFrequency());
            sub.setNextDeliveryDate(calculateNextDeliveryDate(nextDay, sub.getFrequency(), skipped));
            subscriptions.save(sub);
        }

        return new SubscriptionDtos.GenerateOrdersResponse(date, generatedOrderIds.size(), generatedOrderIds);
    }

    private boolean isDueOn(Subscription sub, LocalDate date) {
        if (date.isBefore(sub.getStartDate())) return false;
        if (sub.getEndDate() != null && date.isAfter(sub.getEndDate())) return false;

        // Check if skipped
        if (skipDates.existsBySubscriptionIdAndSkipDate(sub.getId(), date)) {
            return false;
        }

        // Check frequency
        return matchesFrequency(date, sub.getFrequency());
    }

    private boolean matchesFrequency(LocalDate date, SubscriptionFrequency frequency) {
        return switch (frequency) {
            case DAILY -> true;
            case WEEKDAYS -> date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
            case ALTERNATE_DAYS -> true; // on target days
            case WEEKLY -> true; // on chosen day of week
        };
    }

    private LocalDate advanceFrequencyDate(LocalDate current, SubscriptionFrequency frequency) {
        return switch (frequency) {
            case DAILY -> current.plusDays(1);
            case ALTERNATE_DAYS -> current.plusDays(2);
            case WEEKLY -> current.plusWeeks(1);
            case WEEKDAYS -> {
                LocalDate next = current.plusDays(1);
                while (next.getDayOfWeek() == DayOfWeek.SATURDAY || next.getDayOfWeek() == DayOfWeek.SUNDAY) {
                    next = next.plusDays(1);
                }
                yield next;
            }
        };
    }

    private LocalDate calculateNextDeliveryDate(LocalDate fromDate, SubscriptionFrequency frequency, Set<LocalDate> skipped) {
        LocalDate candidate = fromDate;
        for (int i = 0; i < 90; i++) {
            if (matchesFrequency(candidate, frequency) && !skipped.contains(candidate)) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    private com.costonomy.mp.procurement.domain.DeliveryMode parseProcurementMode(String mode) {
        if (mode == null) return com.costonomy.mp.procurement.domain.DeliveryMode.SUPPLIER_DELIVERY;
        try {
            return com.costonomy.mp.procurement.domain.DeliveryMode.valueOf(mode);
        } catch (Exception e) {
            return com.costonomy.mp.procurement.domain.DeliveryMode.SUPPLIER_DELIVERY;
        }
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
