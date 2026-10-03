package com.costonomy.mp.wallet.invoice;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.wallet.invoice.reader.FakeInvoiceReader;
import com.costonomy.mp.wallet.invoice.reader.InvoiceReading;
import com.costonomy.mp.wallet.invoice.service.InvoiceCheck;
import com.costonomy.mp.wallet.invoice.service.InvoiceReviews;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.ItemIn;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.PartyIn;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.ReviewRequest;
import com.costonomy.mp.wallet.invoice.web.InvoiceReviewDtos.SkuIn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** The bill review's draft, checks and money (D-114). */
class InvoiceReviewsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);
    private static final Instant NOW = Instant.parse("2026-10-03T05:00:00Z");
    private static final InvoiceReading READING = new FakeInvoiceReader().read(List.of(), null).reading();

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static ItemIn line(Integer lineNo, Long skuId, String sku, String qty, String amount, String tax) {
        return new ItemIn(lineNo, new SkuIn(skuId, sku, "KG", d("360")), qty == null ? null : d(qty), null,
                amount == null ? null : d(amount), tax == null ? null : d(tax), false);
    }

    private static ReviewRequest request(List<ItemIn> items, String delivery) {
        return new ReviewRequest(0L, new PartyIn(2001L, "Kosta Delights - Sea Food"), "1631", "04/09/26",
                "2026-10-03", "COMPLETED", items, delivery == null ? null : d(delivery), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Runnable call) {
        var e = catchThrowableOfType(call::run, BusinessException.class);
        assertThat(e).describedAs("expected a validation error").isNotNull();
        assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
        return (Map<String, Object>) e.details().get("fields");
    }

    @Test
    @DisplayName("draft from the reading: supplier match, SKU matches, today as stock-in, PENDING, delivery 0, money summed")
    void draftFromReading() {
        var draft = InvoiceReviews.draft(READING, TODAY);

        assertThat(draft.supplier().id()).isEqualTo(2001L);
        assertThat(draft.supplier().name()).isEqualTo("Kosta Delights - Sea Food");
        assertThat(draft.invoiceNumber()).isEqualTo("1631");
        assertThat(draft.invoiceDate()).isEqualTo("04/09/26");
        assertThat(draft.stockInDate()).isEqualTo("2026-10-03");
        assertThat(draft.paymentStatus()).isEqualTo("PENDING");
        assertThat(draft.items()).hasSize(3);
        var first = draft.items().get(0);
        assertThat(first.lineNo()).isEqualTo(1);
        assertThat(first.fromInvoice().name()).isEqualTo("16/20 prawns");
        assertThat(first.fromInvoice().total()).isEqualByComparingTo("1120");
        assertThat(first.sku().id()).isEqualTo(9465L);
        assertThat(first.sku().unitPrice()).isEqualByComparingTo("360");
        assertThat(first.amount()).isEqualByComparingTo("1120");
        assertThat(first.tax()).isEqualByComparingTo("0");
        assertThat(first.lineTotal()).isEqualByComparingTo("1120");
        assertThat(first.itemPrice()).isEqualByComparingTo("560");
        assertThat(first.deviation()).isEqualTo("ABOVE"); // 560 against 360: more than 50% above, as on the cost app
        assertThat(draft.items().get(1).deviation()).isNull(); // 450 against 300: exactly 50%, not more
        assertThat(draft.items().get(2).sku().name()).isEqualTo("Prawns 30/40");
        assertThat(draft.delivery()).isEqualByComparingTo("0");
        assertThat(draft.deliveryOverridden()).isFalse();
        assertThat(draft.subtotal()).isEqualByComparingTo("2820");
        assertThat(draft.tax()).isEqualByComparingTo("0");
        assertThat(draft.total()).isEqualByComparingTo("2820");
        assertThat(draft.reviewedAt()).isNull();
    }

    @Test
    @DisplayName("draft without matches: the vendor name without an id, no SKU; amount = total - tax when only those are read")
    void draftWithoutMatches() {
        var reading = new InvoiceReading("Corner Shop", null, null, null, null, "INR",
                List.of(new InvoiceReading.Item("Tomato", d("3"), "KG", d("40"), d("126"), null, d("6"), null)),
                null, null, d("50"), d("176"));
        var draft = InvoiceReviews.draft(reading, TODAY);
        assertThat(draft.supplier().id()).isNull();
        assertThat(draft.supplier().name()).isEqualTo("Corner Shop");
        assertThat(draft.items().get(0).sku()).isNull();
        assertThat(draft.items().get(0).amount()).isEqualByComparingTo("120");
        assertThat(draft.items().get(0).tax()).isEqualByComparingTo("6");
        assertThat(draft.delivery()).isEqualByComparingTo("50");
        assertThat(draft.total()).isEqualByComparingTo("176");
    }

    @Test
    @DisplayName("draft for an unreadable bill: an empty form, no lines")
    void draftUnreadable() {
        var draft = InvoiceReviews.draft(null, TODAY);
        assertThat(draft.supplier().id()).isNull();
        assertThat(draft.supplier().name()).isEmpty();
        assertThat(draft.items()).isEmpty();
        assertThat(draft.total()).isEqualByComparingTo("0");
        assertThat(draft.stockInDate()).isEqualTo("2026-10-03");
    }

    @Test
    @DisplayName("review money: subtotal = sum of amounts, tax = sum of taxes, total = subtotal + tax + delivery, to the paisa")
    void reviewMoney() {
        var review = InvoiceReviews.review(request(List.of(
                line(1, 9465L, "Prawns 16/20", "2", "1120.10", "56.01"),
                line(2, 152L, "PRAWNS 21/25", "1.5", "450.05", "22.50"),
                line(null, null, "Ice", "1", "0.10", null)), "40.33"), READING, TODAY, NOW, 12L);

        assertThat(review.subtotal()).isEqualByComparingTo("1570.25");
        assertThat(review.tax()).isEqualByComparingTo("78.51");
        assertThat(review.delivery()).isEqualByComparingTo("40.33");
        assertThat(review.deliveryOverridden()).isTrue();
        assertThat(review.total()).isEqualByComparingTo("1689.09");
        assertThat(review.items().get(0).lineTotal()).isEqualByComparingTo("1176.11");
        assertThat(review.items().get(0).itemPrice()).isEqualByComparingTo("588.06");
        assertThat(review.items().get(0).fromInvoice().name()).isEqualTo("16/20 prawns");
        assertThat(review.items().get(2).fromInvoice()).isNull();
        assertThat(review.items().get(2).sku().id()).isNull();
        assertThat(review.items().get(2).tax()).isEqualByComparingTo("0");
        assertThat(review.items().get(2).unit()).isEqualTo("KG"); // from the SKU when the line has none
        assertThat(review.reviewedAt()).isEqualTo(NOW);
        assertThat(review.reviewedBy()).isEqualTo(12L);
        assertThat(review.paymentStatus()).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("delivery equal to what was read is not an override; empty delivery is 0")
    void deliveryOverride() {
        var same = InvoiceReviews.review(request(List.of(line(1, 9465L, "P", "2", "1120", "0")), "0.00"),
                READING, TODAY, NOW, 1L);
        assertThat(same.deliveryOverridden()).isFalse();
        var none = InvoiceReviews.review(request(List.of(line(1, 9465L, "P", "2", "1120", "0")), null),
                READING, TODAY, NOW, 1L);
        assertThat(none.delivery()).isEqualByComparingTo("0");
        assertThat(none.total()).isEqualByComparingTo("1120");
    }

    @Test
    @DisplayName("each field gets a plain sentence")
    void validation() {
        var items = new ArrayList<ItemIn>();
        items.add(new ItemIn(9, null, d("0"), "K".repeat(21), d("-1"), d("1.234"), false));
        items.add(line(1, 9465L, "S".repeat(151), "1", "10000000000000", "0"));
        items.add(line(1, 9465L, "P", "1.2345", "1", "0"));
        var bad = new ReviewRequest(0L, new PartyIn(null, " "), "N".repeat(61), "yesterday", "2026-10-05", "PAID",
                items, d("-5"), d("-1"));

        var f = fields(() -> InvoiceReviews.review(bad, READING, TODAY, NOW, 1L));

        assertThat(f).containsEntry("supplier.name", "Enter the supplier's name.")
                .containsEntry("invoiceNumber", "The invoice number can be at most 60 characters.")
                .containsEntry("invoiceDate", "Enter the invoice date as YYYY-MM-DD.")
                .containsEntry("stockInDate", "The stock-in date cannot be later than tomorrow.")
                .containsEntry("paymentStatus", "Choose PENDING or COMPLETED.")
                .containsEntry("delivery", "Delivery charges cannot be negative.")
                .containsEntry("taxOverride", "Tax cannot be negative.")
                .containsEntry("items[0].lineNo", "This line is not on the bill.")
                .containsEntry("items[0].sku.name", "Choose or name the SKU for this line.")
                .containsEntry("items[0].quantity", "Enter a quantity above 0.")
                .containsEntry("items[0].unit", "The unit can be at most 20 characters.")
                .containsEntry("items[0].amount", "The amount cannot be negative.")
                .containsEntry("items[0].tax", "Use at most 12 digits and 2 decimals.")
                .containsEntry("items[1].sku.name", "The SKU name can be at most 150 characters.")
                .containsEntry("items[1].amount", "Use at most 12 digits and 2 decimals.")
                .containsEntry("items[2].lineNo", "Each bill line can appear once.")
                .containsEntry("items[2].quantity", "Use at most 9 digits and 3 decimals for the quantity.");
    }

    @Test
    @DisplayName("1 to 100 items; dates: as read, or ISO; stock-in may be tomorrow but not later")
    void itemsAndDates() {
        assertThat(fields(() -> InvoiceReviews.review(request(List.of(), null), READING, TODAY, NOW, 1L)))
                .containsEntry("items", "Add at least one item.");
        var many = new ArrayList<ItemIn>();
        for (int i = 0; i < 101; i++) {
            many.add(line(null, null, "x", "1", "1", "0"));
        }
        assertThat(fields(() -> InvoiceReviews.review(request(many, null), READING, TODAY, NOW, 1L)))
                .containsEntry("items", "A bill can have at most 100 items.");

        var iso = new ReviewRequest(0L, new PartyIn(null, "New Supplier"), null, "2026-09-04", "2026-10-04",
                "PENDING", List.of(line(null, null, "x", "1", "1", "0")), null, null);
        var ok = InvoiceReviews.review(iso, null, TODAY, NOW, 1L);
        assertThat(ok.invoiceDate()).isEqualTo("2026-09-04");
        assertThat(ok.stockInDate()).isEqualTo("2026-10-04");
        assertThat(ok.supplier().id()).isNull();

        // Text that is not what was read and not ISO is refused; without a reading, any non-ISO text is.
        var asRead = new ReviewRequest(0L, new PartyIn(null, "S"), null, "04/09/26", "2026-10-03", "PENDING",
                List.of(line(null, null, "x", "1", "1", "0")), null, null);
        assertThat(fields(() -> InvoiceReviews.review(asRead, null, TODAY, NOW, 1L)))
                .containsKey("invoiceDate");
        assertThat(InvoiceReviews.review(asRead, READING, TODAY, NOW, 1L).invoiceDate()).isEqualTo("04/09/26");
    }

    @Test
    @DisplayName("the check uses the reviewed total when there is one")
    void bestTotal() {
        var review = InvoiceReviews.review(request(List.of(line(1, 9465L, "P", "2", "1000", "0")), null),
                READING, TODAY, NOW, 1L);
        assertThat(InvoiceReviews.bestTotal(review, d("2820"))).isEqualByComparingTo("1000");
        assertThat(InvoiceReviews.bestTotal(null, d("2820"))).isEqualByComparingTo("2820");
    }

    /** The H2 bill: three lines with no tax of their own, 180 of tax printed only at the bottom, 2820 in all. */
    private static InvoiceReading billLevelTax() {
        return new InvoiceReading("KOSTA Delights", null, "1631", "04/09/26", null, "INR",
                List.of(new InvoiceReading.Item("16/20 prawns", d("2"), "KG", d("560"), d("1120"), d("1120"), d("0"), null),
                        new InvoiceReading.Item("21/25 prawns", d("2"), "KG", d("450"), d("900"), d("900"), null, null),
                        new InvoiceReading.Item("30/50 prawns", d("2"), "KG", d("310"), d("620"), d("620"), d("0"), null)),
                d("2640"), d("180"), null, d("2820"));
    }

    @Test
    @DisplayName("D-115 (H2): tax printed only for the whole bill is kept: draft tax 180 and total 2820, and saving the untouched draft keeps them")
    void billLevelTaxKept() {
        var draft = InvoiceReviews.draft(billLevelTax(), TODAY);
        assertThat(draft.subtotal()).isEqualByComparingTo("2640");
        assertThat(draft.taxOverride()).isEqualByComparingTo("180");
        assertThat(draft.tax()).isEqualByComparingTo("180");
        assertThat(draft.total()).isEqualByComparingTo("2820");
        assertThat(InvoiceCheck.of(d("2820"), draft.total()).matches()).isTrue();

        // What the screen sends back untouched: the draft's lines and its taxOverride.
        var items = draft.items().stream().map(l -> new ItemIn(l.lineNo(), new SkuIn(null, l.fromInvoice().name(),
                l.unit(), null), l.quantity(), l.unit(), l.amount(), l.tax(), false)).toList();
        var saved = InvoiceReviews.review(new ReviewRequest(0L, new PartyIn(null, "KOSTA Delights"), "1631", "04/09/26",
                "2026-10-03", "PENDING", items, draft.delivery(), draft.taxOverride()), billLevelTax(), TODAY, NOW, 1L);
        assertThat(saved.tax()).isEqualByComparingTo("180");
        assertThat(saved.total()).isEqualByComparingTo("2820");
        assertThat(InvoiceCheck.of(d("2820"), InvoiceReviews.bestTotal(saved, d("2820")), d("2820")).matches()).isTrue();

        // Null: the sum of the line taxes, as before; a value replaces that sum.
        var noOverride = InvoiceReviews.review(new ReviewRequest(0L, new PartyIn(null, "K"), null, null, "2026-10-03",
                "PENDING", items, null, null), billLevelTax(), TODAY, NOW, 1L);
        assertThat(noOverride.tax()).isEqualByComparingTo("0");
        assertThat(noOverride.taxOverride()).isNull();
        assertThat(noOverride.total()).isEqualByComparingTo("2640");
    }

    @Test
    @DisplayName("D-115 (H2): no taxOverride in the draft when the lines carry the tax, or the bill has none")
    void noOverrideWhenLinesCarryTax() {
        assertThat(InvoiceReviews.draft(READING, TODAY).taxOverride()).isNull();
        var lineTax = new InvoiceReading("V", null, null, null, null, null,
                List.of(new InvoiceReading.Item("a", d("1"), "KG", d("100"), d("118"), d("100"), d("18"), null)),
                d("100"), d("18"), null, d("118"));
        var draft = InvoiceReviews.draft(lineTax, TODAY);
        assertThat(draft.taxOverride()).isNull();
        assertThat(draft.tax()).isEqualByComparingTo("18");
        assertThat(draft.total()).isEqualByComparingTo("118");
    }

    @Test
    @DisplayName("D-115 (K4): a SKU's unitPrice may have 4 decimals (the cost app's per-gram prices); 5 is refused")
    void skuPriceFourDecimals() {
        var four = new ItemIn(1, new SkuIn(9465L, "P", "GM", d("0.3612")), d("1"), null, d("1"), d("0"), false);
        assertThat(InvoiceReviews.review(request(List.of(four), null), READING, TODAY, NOW, 1L).items().get(0).sku()
                .unitPrice()).isEqualByComparingTo("0.3612");
        var five = new ItemIn(1, new SkuIn(9465L, "P", "GM", d("0.36125")), d("1"), null, d("1"), d("0"), false);
        assertThat(fields(() -> InvoiceReviews.review(request(List.of(five), null), READING, TODAY, NOW, 1L)))
                .containsEntry("items[0].sku.unitPrice", "Use at most 12 digits and 4 decimals for the price.");
        // Quantity stays at 9 digits and 3 decimals (the mobile app uses the same limit).
        var tenDigits = new ItemIn(null, new SkuIn(null, "P", "KG", null), d("1234567890"), null, d("1"), d("0"), false);
        assertThat(fields(() -> InvoiceReviews.review(request(List.of(tenDigits), null), READING, TODAY, NOW, 1L)))
                .containsEntry("items[0].quantity", "Use at most 9 digits and 3 decimals for the quantity.");
    }

    @Test
    @DisplayName("D-115 (K1): an untouched draft built from any reading is accepted as it is")
    void draftRoundTrips() {
        var odd = new InvoiceReading("V", null, null, null, null, null,
                List.of(new InvoiceReading.Item("a", d("0.3333"), "KG", d("3"), d("1.005"), d("1.005"), null,
                        new InvoiceReading.SkuMatch(5L, "A", "GM", d("0.3612"), null))),
                null, d("0.18"), null, null).capped();
        var draft = InvoiceReviews.draft(odd, TODAY);
        var items = draft.items().stream().map(l -> new ItemIn(l.lineNo(), new SkuIn(l.sku().id(), l.sku().name(),
                l.sku().unit(), l.sku().unitPrice()), l.quantity(), l.unit(), l.amount(), l.tax(), false)).toList();
        var saved = InvoiceReviews.review(new ReviewRequest(0L, new PartyIn(null, "V"), null, null, "2026-10-03",
                "PENDING", items, draft.delivery(), draft.taxOverride()), odd, TODAY, NOW, 1L);
        assertThat(saved.total()).isEqualByComparingTo(draft.total());
        assertThat(saved.items().get(0).quantity()).isEqualByComparingTo("0.333");
    }
}
