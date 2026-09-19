package com.costonomy.mp.delivery.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * What a consignment weighs, for the vehicle and the fee. D-091.
 *
 * <p><b>Derived only when it is not stated.</b> {@code supplier_sku.weight_grams}
 * is the answer whenever a supplier has given one. Everything below is a fallback
 * for a catalogue that mostly predates the column, and the quote records that it
 * fell back — a figure nobody stated should not be indistinguishable from one
 * somebody did.
 *
 * <p>The rules, and how much they are worth trusting:
 *
 * <ul>
 *   <li><b>Mass units are exact.</b> A {@code KG} pack size is kilograms and a
 *       {@code GM} measure is grams. Roughly three quarters of the catalogue.</li>
 *   <li><b>Volumes assume water.</b> One litre is taken as one kilogram, which is
 *       right for water and milk, out by a tenth for most cooking oils, and
 *       nowhere near right for a litre of honey. Good enough to pick a vehicle,
 *       not good enough to bill on.</li>
 *   <li><b>Counts are a guess.</b> A {@code PC} pack says nothing about mass, so
 *       it takes a configured default. This is the case worth removing by filling
 *       in {@code weight_grams}, not by finding a cleverer rule.</li>
 * </ul>
 *
 * <p>The arithmetic lives here rather than in the directory's SQL so that one
 * rule has one home and can be tested without a database.
 */
public final class ConsignmentWeight {

    /** One kilogram, in grams. */
    private static final BigDecimal KG = BigDecimal.valueOf(1000);

    /** Water, near enough, at a gram per millilitre. */
    private static final BigDecimal DENSITY_G_PER_ML = BigDecimal.ONE;

    private ConsignmentWeight() {
    }

    /**
     * One ordered line.
     *
     * @param quantity     packs ordered
     * @param weightGrams  what one pack weighs, when the supplier has said
     * @param packSize     the pack's own number — kilograms for a {@code KG} pack
     * @param packUnit     {@code KG}, {@code LTR}, {@code PC}, {@code PKT}…
     * @param measureValue the amount inside one pack, where a pack has one
     * @param measureUnit  {@code GM}, {@code ML}, or null
     */
    public record Line(
            BigDecimal quantity,
            BigDecimal weightGrams,
            BigDecimal packSize,
            String packUnit,
            BigDecimal measureValue,
            String measureUnit) {
    }

    /** What the whole consignment weighs, and whether any of it was guessed. */
    public record Result(BigDecimal grams, boolean derived) {
    }

    /**
     * @param defaultPieceGrams what to assume for a line that carries no mass at
     *                          all — a count of something
     */
    public static Result of(Collection<Line> lines, BigDecimal defaultPieceGrams) {
        BigDecimal total = BigDecimal.ZERO;
        boolean derived = false;

        for (Line line : lines) {
            BigDecimal quantity = line.quantity() == null ? BigDecimal.ONE : line.quantity();

            BigDecimal perPack = line.weightGrams();
            if (perPack == null) {
                perPack = derivePerPack(line, defaultPieceGrams);
                derived = true;
            }

            total = total.add(perPack.multiply(quantity));
        }

        return new Result(total.setScale(4, RoundingMode.HALF_UP), derived);
    }

    private static BigDecimal derivePerPack(Line line, BigDecimal defaultPieceGrams) {
        BigDecimal packSize = line.packSize() == null ? BigDecimal.ONE : line.packSize();
        String packUnit = line.packUnit() == null ? "" : line.packUnit().toUpperCase();
        String measureUnit = line.measureUnit() == null ? "" : line.measureUnit().toUpperCase();

        // The measure describes what is inside one pack, so it multiplies the
        // pack size: "12 PKT (500 GM)" is six kilograms, not five hundred grams.
        if (line.measureValue() != null) {
            switch (measureUnit) {
                case "GM", "G" -> {
                    return line.measureValue().multiply(packSize);
                }
                case "KG" -> {
                    return line.measureValue().multiply(KG).multiply(packSize);
                }
                case "ML" -> {
                    return line.measureValue().multiply(DENSITY_G_PER_ML).multiply(packSize);
                }
                case "LTR", "L" -> {
                    return line.measureValue().multiply(KG).multiply(packSize);
                }
                default -> { }
            }
        }

        return switch (packUnit) {
            case "KG" -> packSize.multiply(KG);
            case "GM", "G" -> packSize;
            case "LTR", "L" -> packSize.multiply(KG);
            case "ML" -> packSize.multiply(DENSITY_G_PER_ML);
            // A count, a packet with no measure, a case of unspecified something.
            default -> defaultPieceGrams.multiply(packSize);
        };
    }
}
