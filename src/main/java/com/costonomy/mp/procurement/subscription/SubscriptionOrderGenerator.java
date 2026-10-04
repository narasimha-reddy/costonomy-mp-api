package com.costonomy.mp.procurement.subscription;

import com.costonomy.mp.catalog.domain.SupplierOffer;
import com.costonomy.mp.catalog.repository.SupplierOfferRepository;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.delivery.service.DeliveryCharges;
import com.costonomy.mp.delivery.service.DeliveryDirectory;
import com.costonomy.mp.delivery.slot.DeliverySlotRepository;
import com.costonomy.mp.procurement.domain.DeliveryMode;
import com.costonomy.mp.procurement.domain.OrderItemStatus;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.domain.SupplierOrderStatus;
import com.costonomy.mp.procurement.repository.OrderNumberGenerator;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.service.OrderFunding;
import com.costonomy.mp.procurement.service.OrderReleaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;

import static com.costonomy.mp.procurement.subscription.SubscriptionRunException.Outcome.FUNDING_FAILED;
import static com.costonomy.mp.procurement.subscription.SubscriptionRunException.Outcome.SKIPPED_INVALID;
import static com.costonomy.mp.procurement.subscription.SubscriptionRunException.Outcome.SKIPPED_NO_OFFER;

/**
 * Makes one subscription's order for one delivery date, on the same path every other order takes: create it
 * DRAFT, arrange funding through {@link OrderFunding}, release it once funded (D-132, guardrail 16: a
 * supplier never sees an unfunded order). Procurement does not touch wallet or credit directly.
 *
 * <p>One transaction per subscription and date. Anything that stops an order being made throws a
 * {@link SubscriptionRunException}, which rolls back everything written here (the DRAFT order included, so
 * nothing is left behind to block the date) and is recorded afterwards by {@link SubscriptionRunStore}.
 *
 * <p>Lock order is subscription, then order, then wallet or credit, which fits the order → adjustment →
 * wallet → payment order used everywhere else.
 */
@Service
@RequiredArgsConstructor
public class SubscriptionOrderGenerator {

    private final SubscriptionRepository subscriptions;
    private final SubscriptionSkipDateRepository skipDates;
    private final SupplierOrderRepository supplierOrders;
    private final SupplierOrderItemRepository supplierOrderItems;
    private final SupplierOfferRepository supplierOffers;
    private final DeliverySlotRepository deliverySlots;
    private final OrderNumberGenerator orderNumbers;
    private final OrderFunding orderFunding;
    private final OrderReleaseService orderRelease;
    private final DeliveryCharges deliveryCharges;
    private final DeliveryDirectory deliveryPolicies;
    private final SubscriptionRunWriter runWriter;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;

    /**
     * @return the new order's id, or null when there was nothing to do (not active, not due, already made)
     * @throws SubscriptionRunException when an order could not be made
     */
    @Transactional
    public Long generate(Long subscriptionId, LocalDate date) {
        Subscription sub = subscriptions.lockById(subscriptionId).orElse(null);
        if (sub == null || sub.getStatus() != SubscriptionStatus.ACTIVE) {
            return null;
        }
        Set<LocalDate> skipped = skipDates.findBySubscriptionId(subscriptionId).stream()
                .map(SubscriptionSkipDate::getSkipDate).collect(Collectors.toSet());
        if (!SubscriptionSchedule.isDue(sub.getStartDate(), sub.getEndDate(), sub.getFrequency(), date, skipped)) {
            return null;
        }
        if ("GENERATED".equals(runWriter.outcomeOf(subscriptionId, date)) || liveOrderExists(subscriptionId, date)) {
            return null;
        }

        // Validated again here, not only at creation: a slot can be deactivated, a store can stop delivering,
        // and subscriptions made before these rules existed are still in the table.
        DeliveryMode mode = deliveryMode(sub);
        String paymentMethod = paymentMethod(sub);
        validateSlot(sub);

        SupplierOffer offer = supplierOffers.findBySupplierSkuIdAndStatus(sub.getSupplierSkuId(), "ACTIVE")
                .filter(SupplierOffer::isPurchasable)
                .orElseThrow(() -> new SubscriptionRunException(SKIPPED_NO_OFFER,
                        "The supplier has no available offer for this item", null));
        if (offer.getSellingPrice() == null || offer.getSellingPrice().signum() <= 0) {
            throw new SubscriptionRunException(SKIPPED_NO_OFFER, "The supplier's offer has no price", null);
        }
        String unit = skuUnit(sub);

        BigDecimal gstRate = offer.getGstRate() == null ? BigDecimal.ZERO : offer.getGstRate();
        BigDecimal lineValue = Pricing.lineItemValue(offer.getSellingPrice(), sub.getQuantity());
        BigDecimal lineGst = Pricing.lineGst(lineValue, gstRate);
        BigDecimal lineTotal = Pricing.lineTotal(lineValue, lineGst);

        BigDecimal deliveryFee;
        try {
            deliveryFee = deliveryCharges.feeFor(sub.getSupplierStoreId(), mode, lineValue);
        } catch (BusinessException ex) {
            throw new SubscriptionRunException(SKIPPED_INVALID, ex.getMessage(), null);
        }
        BigDecimal total = Pricing.money(lineTotal.add(deliveryFee));

        var order = new SupplierOrder();
        order.setProcurementId(null);
        order.setSupplierStoreId(sub.getSupplierStoreId());
        order.setOutletId(sub.getOutletId());
        order.setOrderNumber(orderNumbers.next());
        order.setStatus(SupplierOrderStatus.DRAFT);
        order.setPaymentMethod(paymentMethod);
        order.setPaymentStatus("PENDING");
        order.setSubtotal(Pricing.money(lineValue));
        order.setGstAmount(Pricing.money(lineGst));
        order.setDeliveryMode(mode);
        order.setDeliveryFee(Pricing.money(deliveryFee));
        order.setDeliverySlotId(sub.getPreferredSlotId());
        order.setScheduledDeliveryDate(date);
        order.setSubscriptionOrder(true);
        order.setSubscriptionId(subscriptionId);
        order.setTotalAmount(total);
        order.setAcceptedAmount(total);
        // Flushed before any money moves: a concurrent run for the same date hits the unique key here, not
        // after a debit.
        supplierOrders.saveAndFlush(order);

        var item = new SupplierOrderItem();
        item.setSupplierOrderId(order.getId());
        item.setCanonicalProductId(sub.getCanonicalProductId());
        item.setSupplierSkuId(sub.getSupplierSkuId());
        item.setRequestedQuantity(sub.getQuantity());
        item.setAcceptedQuantity(sub.getQuantity());
        item.setUnit(unit);
        item.setUnitPriceSnapshot(offer.getSellingPrice());
        item.setGstRateSnapshot(gstRate);
        item.setLineItemValue(lineValue);
        item.setLineGst(lineGst);
        item.setLineTotal(lineTotal);
        item.setStatus(OrderItemStatus.ACCEPTED);
        boolean coldChain = deliveryPolicies.skuRequiresColdChain(sub.getSupplierSkuId());
        item.setRequiresColdChain(coldChain);
        item.setCatchWeight(deliveryPolicies.skuIsCatchWeight(sub.getSupplierSkuId()));
        item.setHsnCode(deliveryPolicies.skuHsnCode(sub.getSupplierSkuId()));
        supplierOrderItems.save(item);
        if (coldChain) {
            order.setHasColdChainItems(true);
        }
        supplierOrders.saveAndFlush(order);

        try {
            orderFunding.arrangeFunding(java.util.List.of(order));
        } catch (BusinessException ex) {
            throw new SubscriptionRunException(FUNDING_FAILED, ex.getMessage(), total);
        }
        if (!orderRelease.releaseIfFunded(order.getId())) {
            throw new SubscriptionRunException(FUNDING_FAILED, "Funding for the order could not be secured", total);
        }

        runWriter.upsert(subscriptionId, date, "GENERATED", null, order.getId(), total);
        sub.setNextDeliveryDate(SubscriptionSchedule.nextOnOrAfter(sub.getStartDate(), sub.getEndDate(),
                sub.getFrequency(), date.plusDays(1), skipped));
        subscriptions.save(sub);
        auditService.record(null, null, "SUBSCRIPTION_ORDER_GENERATED", "SUBSCRIPTION", subscriptionId,
                null, SupplierOrderStatus.CONFIRMED.name(),
                "Order " + order.getOrderNumber() + " for " + date, "SYSTEM");
        return order.getId();
    }

    private boolean liveOrderExists(Long subscriptionId, LocalDate date) {
        Integer count = jdbc.queryForObject("""
                select count(*) from supplier_order
                 where subscription_id = ? and scheduled_delivery_date = ? and status <> 'CANCELLED'
                """, Integer.class, subscriptionId, java.sql.Date.valueOf(date));
        return count != null && count > 0;
    }

    private static DeliveryMode deliveryMode(Subscription sub) {
        DeliveryMode mode;
        try {
            mode = DeliveryMode.valueOf(sub.getDeliveryMode());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new SubscriptionRunException(SKIPPED_INVALID,
                    "Unknown delivery mode '" + sub.getDeliveryMode() + "'", null);
        }
        if (mode == DeliveryMode.COSTONOMY_DELIVERY) {
            throw new SubscriptionRunException(SKIPPED_INVALID,
                    "Subscriptions can't use Costonomy delivery; choose supplier delivery or pickup", null);
        }
        return mode;
    }

    private static String paymentMethod(Subscription sub) {
        String method = sub.getPaymentMethod() == null ? "" : sub.getPaymentMethod().toUpperCase();
        if (!method.equals("WALLET") && !method.equals("CREDIT")) {
            throw new SubscriptionRunException(SKIPPED_INVALID,
                    "Subscriptions can be paid from the wallet or on credit, not '" + sub.getPaymentMethod() + "'", null);
        }
        return method;
    }

    private void validateSlot(Subscription sub) {
        if (sub.getPreferredSlotId() == null) {
            return;
        }
        var slot = deliverySlots.findById(sub.getPreferredSlotId()).orElse(null);
        if (slot == null || !slot.getSupplierStoreId().equals(sub.getSupplierStoreId()) || !slot.isActive()) {
            throw new SubscriptionRunException(SKIPPED_INVALID,
                    "The chosen delivery slot is no longer available", null);
        }
    }

    /** The SKU's own pack unit, never the one the client sent. */
    private String skuUnit(Subscription sub) {
        String unit = jdbc.query("select pack_unit from supplier_sku where id = ? and supplier_store_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, sub.getSupplierSkuId(), sub.getSupplierStoreId());
        if (unit == null || unit.isBlank()) {
            throw new SubscriptionRunException(SKIPPED_INVALID, "The item is no longer sold by this supplier", null);
        }
        return unit;
    }
}
