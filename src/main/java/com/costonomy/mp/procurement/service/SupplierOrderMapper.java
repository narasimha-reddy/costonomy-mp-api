package com.costonomy.mp.procurement.service;

import com.costonomy.mp.catalog.repository.CanonicalProductRepository;
import com.costonomy.mp.catalog.repository.SupplierSkuRepository;
import com.costonomy.mp.procurement.domain.Procurement;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.domain.SupplierOrderItem;
import com.costonomy.mp.procurement.repository.SupplierOrderItemRepository;
import com.costonomy.mp.procurement.repository.SupplierOrderRepository;
import com.costonomy.mp.procurement.web.dto.ProcurementDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

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
    private final SupplierSkuRepository skus;
    private final CanonicalProductRepository products;
    private final ProcurementDirectory directory;

    public ProcurementDtos.SupplierOrderResponse toResponse(SupplierOrder order) {
        var items = supplierOrderItems.findBySupplierOrderId(order.getId());
        var store = directory.stores(List.of(order.getSupplierStoreId()))
                .get(order.getSupplierStoreId());

        Map<Long, String> productNames = new HashMap<>();
        products.findAllById(items.stream()
                        .map(SupplierOrderItem::getCanonicalProductId).distinct().toList())
                .forEach(product -> productNames.put(product.getId(), product.getName()));

        Map<Long, String> skuNames = new HashMap<>();
        skus.findAllById(items.stream().map(SupplierOrderItem::getSupplierSkuId).toList())
                .forEach(sku -> skuNames.put(sku.getId(), sku.getName()));

        return new ProcurementDtos.SupplierOrderResponse(
                order.getId(), order.getOrderNumber(), order.getSupplierStoreId(),
                store == null ? null : store.supplierName(),
                store == null ? null : store.storeName(),
                order.getStatus(), order.getAcceptanceDeadline(), order.getResponseSlaSeconds(),
                order.getSubtotal(), order.getGstAmount(), order.getTotalAmount(),
                order.getAcceptedAmount(), order.getPaymentMethod(), order.getPaymentStatus(),
                items.stream()
                        .map(item -> new ProcurementDtos.SupplierOrderItemResponse(
                                item.getId(), item.getCanonicalProductId(),
                                productNames.get(item.getCanonicalProductId()),
                                skuNames.get(item.getSupplierSkuId()),
                                item.getRequestedQuantity(), item.getAcceptedQuantity(),
                                item.getUnit(), item.getUnitPriceSnapshot(),
                                item.getGstRateSnapshot(), item.getLineTotal(), item.getStatus()))
                        .toList());
    }

    /** The orders a procurement already produced. */
    public ProcurementDtos.SubmitResponse submitResponse(Procurement procurement) {
        return new ProcurementDtos.SubmitResponse(
                procurement.getId(), procurement.getStatus(),
                supplierOrders.findByProcurementId(procurement.getId()).stream()
                        .map(this::toResponse).toList());
    }
}
