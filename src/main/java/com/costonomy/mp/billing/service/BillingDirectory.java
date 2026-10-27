package com.costonomy.mp.billing.service;

import com.costonomy.mp.identity.common.Directory;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Facts from procurement, catalog, supplier and restaurant that billing needs, read through
 * {@link JdbcTemplate} across the module edge like the other directories. Billing no longer imports another
 * module's repositories or entities. The supplier and the restaurant (names, GSTIN, address, state) are
 * costonomy-common-api's, read through {@link Directory} (D-208).
 */
@Service
@RequiredArgsConstructor
public class BillingDirectory {

    private final JdbcTemplate jdbc;
    private final Directory directory;

    /** An order as billing sees it. Amounts the order stores; nothing here is recomputed. */
    public record OrderFacts(Long id, String status, String orderNumber, Long supplierStoreId, Long outletId,
                             BigDecimal deliveryFee, BigDecimal acceptedAmount, BigDecimal weightAdjustment,
                             BigDecimal doorstepRefund, BigDecimal finalPayable) {

        /** What the buyer owes before any doorstep rejection: the supply as it left the supplier. */
        public BigDecimal payableAtDispatch() {
            return finalPayable != null ? finalPayable.add(nz(doorstepRefund))
                    : acceptedAmount.subtract(nz(weightAdjustment));
        }

        private static BigDecimal nz(BigDecimal v) {
            return v == null ? BigDecimal.ZERO : v;
        }
    }

    /** One order line with the figures the order stored for it. */
    public record Line(Long id, String productName, String hsnCode, BigDecimal acceptedQuantity,
                       BigDecimal billableQuantity, String unit, BigDecimal unitPrice, BigDecimal gstRate,
                       BigDecimal lineItemValue, BigDecimal lineGst, BigDecimal lineTotal,
                       BigDecimal rejectedQuantity, BigDecimal refundAmount, String rejectionReason) {

        /** The quantity supplied: the weighed billable quantity when there is one, else what was accepted. */
        public BigDecimal suppliedQuantity() {
            return billableQuantity != null ? billableQuantity : acceptedQuantity;
        }
    }

    public record SupplierParty(Long organizationId, String legalName, String gstin, String address,
                                String state) {
    }

    public record BuyerParty(Long restaurantId, String name, String gstin, String address, String state) {
    }

    public Optional<OrderFacts> order(Long orderId) {
        return jdbc.query("""
                select id, status, order_number, supplier_store_id, outlet_id, delivery_fee, accepted_amount,
                       weight_adjustment_amount, doorstep_refund_amount, final_payable_amount
                  from supplier_order where id = ?
                """,
                (rs, row) -> new OrderFacts(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4),
                        rs.getLong(5), rs.getBigDecimal(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10)),
                orderId).stream().findFirst();
    }

    public List<Line> lines(Long orderId) {
        return jdbc.query("""
                select i.id, p.name, i.hsn_code, i.accepted_quantity, i.billable_quantity, i.unit,
                       i.unit_price_snapshot, i.gst_rate_snapshot, i.line_item_value, i.line_gst, i.line_total,
                       i.doorstep_rejected_qty, i.doorstep_refund_amount, i.doorstep_rejection_reason
                  from supplier_order_item i
             left join canonical_product p on p.id = i.canonical_product_id
                 where i.supplier_order_id = ?
              order by i.id
                """,
                (rs, row) -> new Line(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getBigDecimal(5), rs.getString(6), rs.getBigDecimal(7), rs.getBigDecimal(8),
                        rs.getBigDecimal(9), rs.getBigDecimal(10), rs.getBigDecimal(11), rs.getBigDecimal(12),
                        rs.getBigDecimal(13), rs.getString(14)),
                orderId);
    }

    /** The supplier as registered: legal name, never the display name, and the store's own address and state. */
    public Optional<SupplierParty> supplier(Long supplierStoreId) {
        return directory.outlet(supplierStoreId)
                .filter(s -> "STORE".equals(s.kind()))
                .map(s -> new SupplierParty(s.orgId(), s.orgLegalName(), s.gstin(), address(s), state(s)));
    }

    /** The buyer: the restaurant's legal name when it has one, else its name. */
    public Optional<BuyerParty> buyer(Long outletId) {
        return directory.outlet(outletId)
                .filter(o -> "OUTLET".equals(o.kind()))
                .map(o -> new BuyerParty(o.orgId(),
                        o.orgLegalName() != null && !o.orgLegalName().isBlank() ? o.orgLegalName().trim() : o.orgName(),
                        o.gstin(), address(o), state(o)));
    }

    /** Address lines, city and pincode, skipping what is not set; the state is printed on its own. */
    private static String address(AppOutlet o) {
        return Stream.of(o.addressLine1(), o.addressLine2(), o.city(), o.pincode())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
    }

    /**
     * The state as a name {@link GstState#ofName} reads back to the right code. Common gives the GST code ("36")
     * directly (D-208), so the place of supply is that code's state; only when common has no code is its state
     * name used, matched exactly as a typed name was before. Never guessed: no match leaves the invoice refused.
     */
    private static String state(AppOutlet o) {
        return GstState.ofCode(o.gstStateCode()).map(GstState::stateName).orElse(o.stateName());
    }

    /** The supplier store that owns an order, for the access check; empty when the order does not exist. */
    public Optional<Long> supplierStoreOf(Long orderId) {
        return jdbc.queryForList("select supplier_store_id from supplier_order where id = ?", Long.class, orderId)
                .stream().findFirst();
    }

    /** The outlet that placed an order, for the buyer's read access. */
    public Optional<Long> outletOf(Long orderId) {
        return jdbc.queryForList("select outlet_id from supplier_order where id = ?", Long.class, orderId)
                .stream().findFirst();
    }
}
