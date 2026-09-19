package com.costonomy.mp.procurement.service;

import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.common.domain.Serviceability;
import com.costonomy.mp.catalog.service.SkuDirectory;
import com.costonomy.mp.procurement.domain.Procurement;
import com.costonomy.mp.procurement.domain.Pricing;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders supplier orders as API responses.
 *
 * <p>Its own bean because both {@code ProcurementSubmissionService} (which reads
 * orders) and {@code ProcurementSubmitter} (which creates them) need it. Leaving
 * it on either one made the two depend on each other, and Spring refused to start
 * — a circular dependency is usually a sign that a shared concern is living inside
 * one of its users, which it was.
 */
@Service
@RequiredArgsConstructor
public class SupplierOrderMapper {

    private final SupplierOrderRepository supplierOrders;
    private final SupplierOrderItemRepository supplierOrderItems;
    private final SkuDirectory skuDirectory;
    private final CanonicalProductRepository products;
    private final ProcurementDirectory directory;

    public ProcurementDtos.SupplierOrderResponse toResponse(SupplierOrder order) {
        var items = supplierOrderItems.findBySupplierOrderId(order.getId());
        var store = directory.stores(List.of(order.getSupplierStoreId()))
                .get(order.getSupplierStoreId());
        var outlet = directory.outletSummary(order.getOutletId());

        Map<Long, String> productNames = new HashMap<>();
        Map<Long, String> productImages = new HashMap<>();
        products.findAllById(items.stream()
                        .map(SupplierOrderItem::getCanonicalProductId).distinct().toList())
                .forEach(product -> {
                    productNames.put(product.getId(), product.getName());
                    // Free: the product is already loaded for its name.
                    productImages.put(product.getId(), product.getImageUrl());
                });

        // The same descriptor the request screens use, so a pack reads
        // identically whether somebody is looking at the request or the order
        // that came out of it.
        var descriptors = skuDirectory.describe(
                items.stream().map(SupplierOrderItem::getSupplierSkuId).toList());

        return new ProcurementDtos.SupplierOrderResponse(
                order.getId(), order.getOrderNumber(), order.getSupplierStoreId(),
                store == null ? null : store.supplierName(),
                store == null ? null : store.storeName(),
                order.getOutletId(),
                outlet == null ? null : outlet.outletName(),
                outlet == null ? null : outlet.restaurantName(),
                outlet == null ? null : outlet.locality(),
                outlet == null ? null : outlet.city(),
                distanceKm(store, outlet),
                order.getStatus(), order.getAcceptanceDeadline(), order.getResponseSlaSeconds(),
                order.getCreatedAt(),
                order.getSubtotal(), order.getGstAmount(), order.getTotalAmount(),
                order.getAcceptedAmount(), acceptedSubtotal(items), acceptedGst(items),
                order.getPaymentMethod(), order.getPaymentStatus(),
                order.getDeliveryMode(), order.getDeliveryFee(),
                order.getCancelledBy(), order.getCancellationReason(),
                items.stream()
                        .map(item -> new ProcurementDtos.SupplierOrderItemResponse(
                                item.getId(), item.getCanonicalProductId(),
                                productNames.get(item.getCanonicalProductId()),
                                productImages.get(item.getCanonicalProductId()),
                                descriptors.get(item.getSupplierSkuId()),
                                item.getRequestedQuantity(), item.getAcceptedQuantity(),
                                item.getUnit(), item.getUnitPriceSnapshot(),
                                Pricing.inclusiveOfGst(item.getUnitPriceSnapshot(),
                                        item.getGstRateSnapshot()),
                                item.getGstRateSnapshot(), item.getLineTotal(),
                                acceptedLineTotal(item), item.getStatus().name()))
                        .toList());
    }

    /**
     * What a line is worth at the quantity the supplier committed to.
     *
     * <p>Null until they answer — absent is not zero, and a client showing ₹0.00
     * against an unanswered line would be reporting a refusal that has not
     * happened. Computed through {@link Pricing} from the accepted quantity and
     * the snapshotted price, which is the same arithmetic the acceptance used, so
     * the two cannot disagree.
     */
    private static BigDecimal acceptedLineTotal(SupplierOrderItem item) {
        if (item.getAcceptedQuantity() == null) {
            return null;
        }
        var value = Pricing.lineItemValue(item.getUnitPriceSnapshot(), item.getAcceptedQuantity());
        return Pricing.lineTotal(value, Pricing.lineGst(value, item.getGstRateSnapshot()));
    }

    /** The accepted total before tax. Zero while the order is unanswered. */
    private static BigDecimal acceptedSubtotal(List<SupplierOrderItem> items) {
        return items.stream()
                .filter(item -> item.getAcceptedQuantity() != null)
                .map(item -> Pricing.lineItemValue(
                        item.getUnitPriceSnapshot(), item.getAcceptedQuantity()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** GST on the accepted lines, summed per line as the acceptance summed it. */
    private static BigDecimal acceptedGst(List<SupplierOrderItem> items) {
        return items.stream()
                .filter(item -> item.getAcceptedQuantity() != null)
                .map(item -> Pricing.lineGst(
                        Pricing.lineItemValue(item.getUnitPriceSnapshot(), item.getAcceptedQuantity()),
                        item.getGstRateSnapshot()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The leg from the store to the outlet, in kilometres to one decimal.
     *
     * <p>Null when either end has no coordinates — an outlet that was never
     * located has no distance, and rounding an unknown to "0.0 km" would tell a
     * supplier the order is next door (doc 07 §4: never fabricate a signal).
     */
    private BigDecimal distanceKm(ProcurementDirectory.StoreInfo store,
                                  ProcurementDirectory.OutletSummary outlet) {
        if (store == null || outlet == null) {
            return null;
        }
        Double km = Serviceability.distanceKm(
                store.latitude(), store.longitude(), outlet.latitude(), outlet.longitude());
        return km == null ? null : BigDecimal.valueOf(km).setScale(1, RoundingMode.HALF_UP);
    }

    /**
     * The orders a procurement already produced.
     *
     * <p>Returns no payment intents: this replays an existing submission, and the
     * intents were handed to the client the first time. Re-issuing them would
     * invite a second checkout against an order already being paid for.
     */
    public ProcurementDtos.SubmitResponse submitResponse(Procurement procurement) {
        return new ProcurementDtos.SubmitResponse(
                procurement.getId(), procurement.getStatus(),
                supplierOrders.findByProcurementId(procurement.getId()).stream()
                        .map(this::toResponse).toList(),
                List.of());
    }
}
