package com.costonomy.mp.delivery.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.costonomy.mp.delivery.domain.ConsignmentWeight;

import java.math.BigDecimal;
import java.util.List;

/**
 * Facts from other modules that delivery needs to read.
 *
 * <p>{@link JdbcTemplate} across the module edge, like {@code ProcurementDirectory}
 * and {@code CreditDirectory}.
 */
@Service
@RequiredArgsConstructor
public class DeliveryDirectory {

    private final JdbcTemplate jdbc;

    public record OrderInfo(
            Long orderId,
            String orderNumber,
            String status,
            Long outletId,
            Long supplierStoreId,
            BigDecimal deliveryFee,
            String deliveryMode,
            BigDecimal estimatedWeightKg,
            BigDecimal estimatedVolumeCbm,
            boolean requiresColdChain) {

        public OrderInfo(Long orderId, String orderNumber, String status, Long outletId,
                         Long supplierStoreId, BigDecimal deliveryFee, String deliveryMode,
                         BigDecimal estimatedWeightKg, BigDecimal estimatedVolumeCbm) {
            this(orderId, orderNumber, status, outletId, supplierStoreId, deliveryFee, deliveryMode,
                 estimatedWeightKg, estimatedVolumeCbm, false);
        }
    }

    public OrderInfo order(Long supplierOrderId) {
        var rows = jdbc.query("""
                select id, order_number, status, outlet_id, supplier_store_id,
                       delivery_fee, delivery_mode, coalesce(has_cold_chain_items, 0)
                  from supplier_order where id = ?
                """,
                (rs, row) -> new OrderInfo(rs.getLong(1), rs.getString(2), rs.getString(3),
                        rs.getLong(4), rs.getLong(5), rs.getBigDecimal(6), rs.getString(7),
                        calculateWeightKg(rs.getLong(1)), null, rs.getBoolean(8)),
                supplierOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean intentRequiresColdChain(Long intentId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from intent_item i
                  join supplier_sku s on s.id = i.supplier_sku_id
                 where i.intent_id = ? and s.requires_cold_chain = 1
                """, Integer.class, intentId);
        return count != null && count > 0;
    }

    public boolean skuRequiresColdChain(Long skuId) {
        if (skuId == null) {
            return false;
        }
        var list = jdbc.queryForList("""
                select requires_cold_chain from supplier_sku where id = ?
                """, Boolean.class, skuId);
        return !list.isEmpty() && Boolean.TRUE.equals(list.get(0));
    }

    /**
     * The HSN code the order line should carry: the SKU's own, else its product's, else null. Never defaulted: a
     * line with none is refused when its tax invoice is requested, not given somebody else's code.
     */
    public String skuHsnCode(Long skuId) {
        if (skuId == null) {
            return null;
        }
        var list = jdbc.queryForList("""
                select coalesce(nullif(trim(s.hsn_code), ''), nullif(trim(p.hsn_code), ''))
                  from supplier_sku s left join canonical_product p on p.id = s.canonical_product_id
                 where s.id = ?
                """, String.class, skuId);
        return list.isEmpty() ? null : list.get(0);
    }

    public boolean skuIsCatchWeight(Long skuId) {
        if (skuId == null) {
            return false;
        }
        var list = jdbc.queryForList("""
                select is_catch_weight from supplier_sku where id = ?
                """, Boolean.class, skuId);
        return !list.isEmpty() && Boolean.TRUE.equals(list.get(0));
    }



    /**
     * Compute total payload weight in KG by summing line item pack quantities multiplied
     * by their SKU pack size or measure value.
     */
    public BigDecimal calculateWeightKg(Long supplierOrderId) {
        var items = jdbc.query("""
                select coalesce(soi.accepted_quantity, soi.requested_quantity) as qty,
                       soi.unit,
                       sku.pack_size,
                       sku.pack_unit,
                       sku.measure_value,
                       sku.measure_unit
                  from supplier_order_item soi
                  join supplier_sku sku on sku.id = soi.supplier_sku_id
                 where soi.supplier_order_id = ?
                """,
                (rs, row) -> {
                    BigDecimal qty = rs.getBigDecimal(1);
                    String itemUnit = rs.getString(2);
                    BigDecimal packSize = rs.getBigDecimal(3);
                    String packUnit = rs.getString(4);
                    BigDecimal measureValue = rs.getBigDecimal(5);
                    String measureUnit = rs.getString(6);
                    return lineWeightKg(qty, itemUnit, packSize, packUnit, measureValue, measureUnit);
                },
                supplierOrderId);

        if (items.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return items.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal lineWeightKg(BigDecimal qty, String itemUnit, BigDecimal packSize,
                                           String packUnit, BigDecimal measureValue, String measureUnit) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        // If measure unit is given (e.g. 1 PKT of 500 GM), measure takes precedence
        if (measureValue != null && measureUnit != null) {
            BigDecimal perPackKg = convertToKg(measureValue, measureUnit);
            return qty.multiply(perPackKg);
        }
        // If pack unit is a weight unit (e.g. KG, GM)
        if (packUnit != null) {
            BigDecimal perPackKg = convertToKg(packSize != null ? packSize : BigDecimal.ONE, packUnit);
            return qty.multiply(perPackKg);
        }
        // Fallback to item unit
        if (itemUnit != null) {
            return qty.multiply(convertToKg(BigDecimal.ONE, itemUnit));
        }
        return qty;
    }

    private static BigDecimal convertToKg(BigDecimal value, String unitStr) {
        if (value == null) return BigDecimal.ZERO;
        String u = unitStr.toUpperCase().trim();
        return switch (u) {
            case "KG", "KGS", "KILOGRAM", "KILO" -> value;
            case "GM", "GMS", "G", "GRAM", "GRAMS" -> value.divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
            case "LTR", "L", "LITRE", "LITER" -> value; // 1 L ~ 1 KG assumption
            case "ML", "MILLILITRE", "MILLILITER" -> value.divide(BigDecimal.valueOf(1000), 4, java.math.RoundingMode.HALF_UP);
            case "LB", "LBS", "POUND" -> value.multiply(BigDecimal.valueOf(0.453592)).setScale(4, java.math.RoundingMode.HALF_UP);
            case "OZ", "OUNCE" -> value.multiply(BigDecimal.valueOf(0.0283495)).setScale(4, java.math.RoundingMode.HALF_UP);
            default -> value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ONE; // default 1 KG estimate per unit
        };
    }

    /** A pickup or drop point, with someone to call when the driver cannot find it. */
    public record Place(
            String name,
            String address,
            BigDecimal latitude,
            BigDecimal longitude,
            String contactName,
            String contactPhone) {
    }

    public Place pickupFor(Long supplierStoreId) {
        var rows = jdbc.query("""
                select s.name, concat_ws(', ', s.address_line1, s.address_line2, s.city,
                       s.state, s.pincode), s.latitude, s.longitude,
                       s.contact_name, s.contact_phone
                  from supplier_store s where s.id = ?
                """,
                (rs, row) -> new Place(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)),
                supplierStoreId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Place dropFor(Long outletId) {
        var rows = jdbc.query("""
                select o.name, concat_ws(', ', o.address_line1, o.address_line2, o.city,
                       o.state, o.pincode), o.latitude, o.longitude,
                       o.contact_name, o.contact_phone
                  from outlet o where o.id = ?
                """,
                (rs, row) -> new Place(rs.getString(1), rs.getString(2), rs.getBigDecimal(3),
                        rs.getBigDecimal(4), rs.getString(5), rs.getString(6)),
                outletId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** City, state and pincode of a supplier store, or null when there is no such store. */
    public com.costonomy.mp.delivery.provider.DeliveryProvider.Locality pickupLocality(Long supplierStoreId) {
        var rows = jdbc.query("""
                select city, state, pincode from supplier_store where id = ?
                """,
                (rs, row) -> new com.costonomy.mp.delivery.provider.DeliveryProvider.Locality(
                        rs.getString(1), rs.getString(2), rs.getString(3)),
                supplierStoreId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** City, state and pincode of an outlet, or null when there is no such outlet. */
    public com.costonomy.mp.delivery.provider.DeliveryProvider.Locality dropLocality(Long outletId) {
        var rows = jdbc.query("""
                select city, state, pincode from outlet where id = ?
                """,
                (rs, row) -> new com.costonomy.mp.delivery.provider.DeliveryProvider.Locality(
                        rs.getString(1), rs.getString(2), rs.getString(3)),
                outletId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * The value of the goods in a supplier order: accepted amount including GST, excluding
     * delivery. Same convention as commission (CommissionService). Null when the order
     * does not exist or has no accepted amount yet; never a default.
     */
    public BigDecimal goodsValue(Long supplierOrderId) {
        if (supplierOrderId == null) {
            return null;
        }
        var rows = jdbc.query("""
                select accepted_amount - delivery_fee from supplier_order where id = ?
                """,
                (rs, row) -> rs.getBigDecimal(1), supplierOrderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * A store's delivery settings. Doc 01 §20.
     *
     * <p>A store with no row has not configured delivery. Costonomy delivery is
     * the default there — the supplier has not said they will carry it themselves,
     * and assuming they will would leave an order nobody collects.
     */
    public record DeliveryPolicy(
            boolean ownDeliveryEnabled,
            boolean costonomyDeliveryEnabled,
            BigDecimal ownDeliveryFee,
            BigDecimal ownDeliveryMinOrderValue,
            BigDecimal minOrderValue,
            BigDecimal freeDeliveryThreshold,
            BigDecimal maxDeliveryRadiusKm) {

        public static final DeliveryPolicy DEFAULT = new DeliveryPolicy(
                false, true, BigDecimal.ZERO, null, BigDecimal.ZERO, null, null);
    }

    public DeliveryPolicy deliveryPolicy(Long supplierStoreId) {
        var rows = jdbc.query("""
                select own_delivery_enabled, costonomy_delivery_enabled, own_delivery_fee,
                       own_delivery_min_order_value, coalesce(min_order_value, 0), free_delivery_threshold,
                       max_delivery_radius_km
                  from supplier_delivery_policy where supplier_store_id = ?
                """,
                (rs, row) -> new DeliveryPolicy(rs.getBoolean(1), rs.getBoolean(2),
                        rs.getBigDecimal(3), rs.getBigDecimal(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getBigDecimal(7)),
                supplierStoreId);
        return rows.isEmpty() ? DeliveryPolicy.DEFAULT : rows.get(0);
    }

    // ── What is being carried ───────────────────────────────────────────

    /**
     * The lines of a request, as weighable rows. D-091.
     *
     * <p>Quantities are the requested ones: a quote is taken before the order
     * exists, so there is nothing else to weigh — and the restaurant orders what
     * the supplier offered, which is at most this.
     */
    public List<ConsignmentWeight.Line> intentLines(Long intentId) {
        return jdbc.query("""
                select i.requested_quantity, s.weight_grams, s.pack_size, s.pack_unit,
                       s.measure_value, s.measure_unit
                  from intent_item i
                  join supplier_sku s on s.id = i.supplier_sku_id
                 where i.intent_id = ?
                """,
                (rs, row) -> new ConsignmentWeight.Line(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6)),
                intentId);
    }

    /**
     * What an order's accepted quantities weigh.
     *
     * <p>Accepted, not requested: by booking time the supplier has committed, and
     * a courier is carrying what is in the van.
     */
    public BigDecimal consignmentWeightGrams(Long supplierOrderId) {
        var lines = jdbc.query("""
                select coalesce(i.accepted_quantity, i.requested_quantity),
                       s.weight_grams, s.pack_size, s.pack_unit,
                       s.measure_value, s.measure_unit
                  from supplier_order_item i
                  join supplier_sku s on s.id = i.supplier_sku_id
                 where i.supplier_order_id = ?
                """,
                (rs, row) -> new ConsignmentWeight.Line(
                        rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6)),
                supplierOrderId);
        return ConsignmentWeight.of(lines, DEFAULT_PIECE_GRAMS).grams();
    }

    /**
     * What to assume a countable pack weighs when nothing says.
     *
     * <p>Half a kilo: heavy enough that a large order of them does not quote as a
     * bike run, light enough not to price every crate as a truck. The real fix is
     * {@code supplier_sku.weight_grams}, not a better constant.
     */
    public static final BigDecimal DEFAULT_PIECE_GRAMS = BigDecimal.valueOf(500);
}
