package com.costonomy.mp.catalog.domain;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The units a product may be sold in. Doc 01 §7.
 *
 * <p>This exists because the unit is what makes a comparison a comparison. Two
 * suppliers' paneer map to one canonical product so a restaurant can hold their
 * prices side by side, and that only means anything if both are quoting the same
 * measure. Free text could not do that: {@code KG}, {@code Kg}, {@code kg} and
 * {@code kgs} are four units to a database and one to a person, and nothing would
 * have noticed until a restaurant compared two prices that were not comparable.
 *
 * <p><b>A pack unit is not always a measure.</b> "1 PKT" says how the goods are
 * bundled and nothing about how much is in the bundle; "1 KG" says both. So the
 * container units below carry a separate measure — 1 PKT <em>of 500 GM</em> — and
 * the ones that are already measures must not, because two statements of the same
 * quantity are two chances to disagree.
 */
public enum Unit {

    // ── Weight ──────────────────────────────────────────────────────────
    GM, KG, OZ, LB,

    // ── Volume ──────────────────────────────────────────────────────────
    ML, LTR,

    // ── Count ───────────────────────────────────────────────────────────
    PC, DOZEN, PAIR,

    // ── Containers and bundles ──────────────────────────────────────────
    BOTTLE, PKT, CASE, BULK, TIN, BUNDLE;

    /**
     * Pack units whose name says how the goods are bundled, not how much is in
     * them. A SKU packed in one of these must also state its measure.
     *
     * <p>{@code BOTTLE} is deliberately <em>not</em> here even though it is a
     * container: a bottle is how a restaurant orders oil and "1 BOTTLE of 1 LTR"
     * is worth saying, but it is also a unit people quote on its own. It may
     * carry a measure and is not required to. The five below cannot be quoted
     * alone — "a case" of anything is not an amount.
     */
    private static final Set<Unit> NEEDS_MEASURE =
            Set.of(PKT, CASE, BULK, TIN, BUNDLE);

    /**
     * What a container's contents may be measured in.
     *
     * <p>A subset, because the measure has to be something a person can add up.
     * "1 CASE of 24 PKT" and "1 PKT of 500 GM" are both useful; "1 CASE of 2
     * BULK" is a riddle. {@code PKT} and {@code BOTTLE} appear here because a
     * case of packets and a case of bottles are ordinary; the other containers do
     * not.
     */
    private static final Set<Unit> MEASURES =
            Set.of(GM, KG, ML, LTR, PC, BOTTLE, PKT);

    /**
     * Spellings that arrive from elsewhere and mean one of ours.
     *
     * <p>{@code L} and {@code PIECE} are here because they are what this database
     * held before this enum existed; the rest are what people type. Mapping them
     * is not leniency — it is the difference between an import that works and one
     * that rejects a supplier's whole file over a spelling.
     */
    private static final Map<String, Unit> ALIASES = Map.ofEntries(
            Map.entry("L", LTR), Map.entry("LITRE", LTR), Map.entry("LITER", LTR),
            Map.entry("LT", LTR), Map.entry("LTRS", LTR), Map.entry("LITRES", LTR),
            Map.entry("ML", ML), Map.entry("MILLILITRE", ML), Map.entry("MILLILITER", ML),
            Map.entry("G", GM), Map.entry("GRAM", GM), Map.entry("GRAMS", GM),
            Map.entry("GMS", GM),
            Map.entry("KGS", KG), Map.entry("KILOGRAM", KG), Map.entry("KILO", KG),
            Map.entry("PIECE", PC), Map.entry("PIECES", PC), Map.entry("PCS", PC),
            Map.entry("NOS", PC), Map.entry("NO", PC),
            Map.entry("DOZ", DOZEN), Map.entry("DZ", DOZEN),
            Map.entry("PACKET", PKT), Map.entry("PACK", PKT), Map.entry("PKTS", PKT),
            Map.entry("BOX", CASE), Map.entry("CTN", CASE), Map.entry("CARTON", CASE),
            Map.entry("BTL", BOTTLE), Map.entry("BOTTLES", BOTTLE),
            Map.entry("OUNCE", OZ), Map.entry("POUND", LB), Map.entry("LBS", LB),
            Map.entry("LOOSE", BULK));

    /** Whether a SKU packed in this unit must also state what is inside it. */
    public boolean requiresMeasure() {
        return NEEDS_MEASURE.contains(this);
    }

    /** Whether this unit may be used to measure a container's contents. */
    public boolean canMeasure() {
        return MEASURES.contains(this);
    }

    public static List<Unit> packUnits() {
        return List.of(values());
    }

    public static List<Unit> measureUnits() {
        return List.of(values()).stream().filter(Unit::canMeasure).toList();
    }

    /**
     * Parse a unit, tolerating case, whitespace and the spellings people use.
     *
     * @throws BusinessException naming the field, because "invalid unit" in a
     *                           twelve-column import tells nobody which column
     */
    public static Unit parse(String raw, String field) {
        Unit unit = parseOrNull(raw);
        if (unit == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "%s is not a unit we know. Use one of: %s."
                            .formatted(field, String.join(", ",
                                    List.of(values()).stream().map(Enum::name).toList())));
        }
        return unit;
    }

    /** Null rather than throwing, for callers that have their own error shape. */
    public static Unit parseOrNull(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().toUpperCase(Locale.ROOT).replace(".", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        for (Unit unit : values()) {
            if (unit.name().equals(cleaned)) {
                return unit;
            }
        }
        return ALIASES.get(cleaned);
    }
}
