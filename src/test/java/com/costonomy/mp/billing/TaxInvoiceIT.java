package com.costonomy.mp.billing;

import com.costonomy.mp.billing.service.FiscalYear;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Statutory tax invoices and credit notes with the feature on (D-133). Every test asserts the rows, not just the status. */
@TestPropertySource(properties = "costonomy.mp.billing.tax-invoices.enabled=true")
class TaxInvoiceIT extends TaxInvoiceTestBase {

    private static String fy() {
        return FiscalYear.label(FiscalYear.startYear(Instant.now()));
    }

    // ── who and when ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("who may generate, and when")
    class Access {

        @Test
        @DisplayName("a buyer cannot generate: 404, no invoice, no number used; the supplier can")
        void buyerCannotGenerate() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            assertThat(post(p.buyer().token(), generatePath(p), Map.of()).status()).isEqualTo(404);
            assertThat(invoiceRows(p.orderId())).isZero();
            assertThat(sequenceRows(seller.gstin())).isZero();

            assertThat(post(seller.token(), generatePath(p), Map.of()).status()).isEqualTo(200);
            assertThat(invoiceRows(p.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("another supplier cannot generate, but both sides can read the invoice")
        void otherSupplierCannotGenerate() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var stranger = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            assertThat(post(stranger.token(), generatePath(p), Map.of()).status()).isEqualTo(404);
            assertThat(invoiceRows(p.orderId())).isZero();

            assertThat(post(seller.token(), generatePath(p), Map.of()).status()).isEqualTo(200);
            String read = "/api/v1/supplier-orders/" + p.orderId() + "/tax-invoice";
            assertThat(get(seller.token(), read).status()).isEqualTo(200);
            assertThat(get(p.buyer().token(), read).status()).isEqualTo(200);
            assertThat(get(stranger.token(), read).status()).isEqualTo(404);
        }

        @Test
        @DisplayName("an order that is not ready is refused with 422 and leaves no invoice and no number used")
        void notReadyIsRefused() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);    // CONFIRMED

            var refused = post(seller.token(), generatePath(p), Map.of());

            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.errorCode()).isEqualTo("TAX_INVOICE_NOT_ALLOWED");
            assertThat(invoiceRows(p.orderId())).isZero();
            assertThat(sequenceRows(seller.gstin())).isZero();
        }
    }

    // ── nothing invented ─────────────────────────────────────────────────

    @Nested
    @DisplayName("nothing is invented")
    class NoFabrication {

        @Test
        @DisplayName("a supplier without a GSTIN: 422 naming exactly that, no invoice, no number used")
        void missingGstin() throws Exception {
            var seller = newSeller(null, "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            var refused = post(seller.token(), generatePath(p), Map.of());

            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.errorCode()).isEqualTo("TAX_INVOICE_DATA_MISSING");
            assertThat(refused.body().at("/error/details/missing").toString()).isEqualTo("[\"supplier.gstin\"]");
            assertThat(invoiceRows(p.orderId())).isZero();
            // No number was used under any blank scope either.
            assertThat(jdbc.queryForObject(
                    "select count(*) from document_sequence where scope_key is null or trim(scope_key) = ''",
                    Integer.class)).isZero();
        }

        @Test
        @DisplayName("a line with no HSN code is named, not given 9968")
        void missingHsn() throws Exception {
            var seller = newSeller(freshGstin(), null);
            var p = placeNew(seller);
            readyAt(p, "9.6");

            var refused = post(seller.token(), generatePath(p), Map.of());

            assertThat(refused.status()).isEqualTo(422);
            assertThat(refused.body().at("/error/details/missing/0").asText()).contains(".hsnCode");
            assertThat(invoiceRows(p.orderId())).isZero();
            assertThat(jdbc.queryForObject("select count(*) from tax_invoice_item where hsn_code = '9968'",
                    Integer.class)).isZero();
        }

        @Test
        @DisplayName("the schema has no HSN default and no cascading delete on invoice or credit note items")
        void schemaHasNoDefaultsOrCascades() {
            assertThat(jdbc.queryForList("""
                    select column_default from information_schema.columns
                     where table_schema = database() and column_name = 'hsn_code'
                       and table_name in ('tax_invoice_item', 'credit_note_item')""", String.class))
                    .hasSize(2).containsOnlyNulls();
            assertThat(jdbc.queryForList("""
                    select delete_rule from information_schema.referential_constraints
                     where constraint_schema = database()
                       and constraint_name in ('fk_tax_invoice_item_invoice', 'fk_credit_note_item_note')""",
                    String.class)).hasSize(2).doesNotContain("CASCADE");
        }
    }

    // ── amounts ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("amounts")
    class Amounts {

        @Test
        @DisplayName("10 kg accepted, 9.6 kg weighed: the invoice is 1,008.00, with the supplier's legal details")
        void weighedInvoice() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            var reply = post(seller.token(), generatePath(p), Map.of());

            assertThat(reply.status()).isEqualTo(200);
            var invoice = reply.body().at("/data");
            assertThat(invoice.get("totalAmount").decimalValue()).isEqualByComparingTo("1008.00");
            assertThat(invoice.get("taxableAmount").decimalValue()).isEqualByComparingTo("960.00");
            assertThat(invoice.get("cgstAmount").decimalValue()).isEqualByComparingTo("24.00");
            assertThat(invoice.get("sgstAmount").decimalValue()).isEqualByComparingTo("24.00");
            assertThat(invoice.get("supplierGstin").asText()).isEqualTo(seller.gstin());
            assertThat(invoice.get("supplierStateCode").asText()).isEqualTo("36");
            assertThat(invoice.get("placeOfSupply").asText()).isEqualTo("36-Telangana");
            assertThat(invoice.get("isInterState").asBoolean()).isFalse();
            assertThat(invoice.at("/items/0/hsnCode").asText()).isEqualTo("0207");
            assertThat(invoice.at("/items/0/quantity").decimalValue()).isEqualByComparingTo("9.6");
            // The supplier's registered legal name, not a display name or a placeholder.
            assertThat(invoice.get("supplierName").asText()).startsWith("Fresh Meats Pvt Ltd");

            // And it is the order's own figure.
            assertThat(jdbc.queryForObject("select final_payable_amount from supplier_order where id = ?",
                    BigDecimal.class, p.orderId())).isEqualByComparingTo("1008.00");
        }

        @Test
        @DisplayName("a 10.4 kg reading on 10 kg accepted is invoiced at the accepted 10 kg: 1,050.00")
        void overweightInvoicedAtAccepted() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "10.4");

            var invoice = post(seller.token(), generatePath(p), Map.of()).body().at("/data");

            assertThat(invoice.get("totalAmount").decimalValue()).isEqualByComparingTo("1050.00");
            assertThat(invoice.at("/items/0/quantity").decimalValue()).isEqualByComparingTo("10");
        }

        @Test
        @DisplayName("the invoice number is at most 16 characters and the invoice is returned again, not duplicated")
        void numberFormatAndIdempotence() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            String first = post(seller.token(), generatePath(p), Map.of()).body().at("/data/invoiceNumber").asText();
            String again = post(seller.token(), generatePath(p), Map.of()).body().at("/data/invoiceNumber").asText();

            assertThat(first).isEqualTo("INV/" + fy() + "/000001").hasSizeLessThanOrEqualTo(16);
            assertThat(again).isEqualTo(first);
            assertThat(invoiceRows(p.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select next_value from document_sequence where scope_key = ?",
                    Long.class, seller.gstin())).isEqualTo(2L);
        }
    }

    // ── numbering and races ──────────────────────────────────────────────

    @Nested
    @DisplayName("numbering and races")
    class Numbering {

        @Test
        @DisplayName("a supplier's numbers run 1, 2, 3 with no gaps; another supplier starts again at 1")
        void sequentialPerSupplier() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var other = newSeller(freshGstin(), "0207");
            var buyer = newBuyer();
            var numbers = new ArrayList<String>();
            for (int i = 0; i < 3; i++) {
                var p = place(buyer, seller);
                readyAt(p, "9.6");
                numbers.add(post(seller.token(), generatePath(p), Map.of()).body().at("/data/invoiceNumber").asText());
            }
            var po = place(buyer, other);
            readyAt(po, "9.6");
            String otherNumber = post(other.token(), generatePath(po), Map.of()).body().at("/data/invoiceNumber").asText();

            assertThat(numbers).containsExactly("INV/" + fy() + "/000001", "INV/" + fy() + "/000002",
                    "INV/" + fy() + "/000003");
            assertThat(otherNumber).isEqualTo("INV/" + fy() + "/000001");
        }

        @Test
        @DisplayName("two generations of one order at once: both get 200, one row, one number used")
        void concurrentGenerations() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            var pool = Executors.newFixedThreadPool(2);
            var start = new CountDownLatch(1);
            List<Future<Reply>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return post(seller.token(), generatePath(p), Map.of());
                }));
            }
            start.countDown();
            var first = futures.get(0).get();
            var second = futures.get(1).get();
            pool.shutdown();

            assertThat(first.status()).isEqualTo(200);
            assertThat(second.status()).isEqualTo(200);
            assertThat(first.body().at("/data/invoiceNumber").asText())
                    .isEqualTo(second.body().at("/data/invoiceNumber").asText());
            assertThat(invoiceRows(p.orderId())).isEqualTo(1);
            // The loser's number rolled back with its insert: the next free number is 2, not 3.
            assertThat(jdbc.queryForObject("select next_value from document_sequence where scope_key = ?",
                    Long.class, seller.gstin())).isEqualTo(2L);
        }
    }

    // ── credit notes ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("credit notes")
    class CreditNotes {

        @Test
        @DisplayName("a doorstep rejection after the invoice: invoice minus credit note equals the final payable, note linked")
        void rejectionAfterInvoice() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");
            assertThat(post(seller.token(), generatePath(p), Map.of()).status()).isEqualTo(200);

            assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);
            deliverReceivingCompleted(p.orderId());

            var note = jdbc.queryForMap("select * from credit_note where supplier_order_id = ?", p.orderId());
            assertThat((BigDecimal) note.get("total_refund_amount")).isEqualByComparingTo("10.50");
            assertThat(note.get("credit_note_number").toString()).isEqualTo("CN/" + fy() + "/000001");
            assertThat(note.get("tax_invoice_id")).isNotNull();
            assertThat(note.get("tax_invoice_number")).isEqualTo("INV/" + fy() + "/000001");
            BigDecimal invoiceTotal = jdbc.queryForObject("select total_amount from tax_invoice where supplier_order_id = ?",
                    BigDecimal.class, p.orderId());
            BigDecimal finalPayable = jdbc.queryForObject("select final_payable_amount from supplier_order where id = ?",
                    BigDecimal.class, p.orderId());
            assertThat(invoiceTotal.subtract((BigDecimal) note.get("total_refund_amount")))
                    .isEqualByComparingTo(finalPayable).isEqualByComparingTo("997.50");
            assertThat(jdbc.queryForObject("""
                    select amount from order_adjustment where supplier_order_id = ? and reason = 'DOORSTEP_REJECTION'""",
                    BigDecimal.class, p.orderId())).isEqualByComparingTo("10.50");

            // Replaying the event issues no second note.
            deliverReceivingCompleted(p.orderId());
            assertThat(creditNoteRows(p.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("a rejection before any invoice leaves the note unlinked, and generating the invoice links it")
        void rejectionBeforeInvoice() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);
            deliverReceivingCompleted(p.orderId());
            assertThat(creditNoteRows(p.orderId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("select tax_invoice_id from credit_note where supplier_order_id = ?",
                    Long.class, p.orderId())).isNull();

            assertThat(post(seller.token(), generatePath(p), Map.of()).status()).isEqualTo(200);

            assertThat(jdbc.queryForObject("select tax_invoice_id from credit_note where supplier_order_id = ?",
                    Long.class, p.orderId())).isNotNull();
            assertThat(creditNoteRows(p.orderId())).isEqualTo(1);
        }

        @Test
        @DisplayName("billing cannot fail the check-in: with no supplier GSTIN the order still completes, and the note comes later")
        void billingNeverFailsTheCheckIn() throws Exception {
            var seller = newSeller(null, "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");

            assertThat(receive(p, "9.5", "0", "0.1").status()).isEqualTo(200);
            deliverReceivingCompleted(p.orderId());

            assertThat(jdbc.queryForObject("select status from supplier_order where id = ?", String.class, p.orderId()))
                    .isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("""
                    select amount from order_adjustment where supplier_order_id = ? and reason = 'DOORSTEP_REJECTION'""",
                    BigDecimal.class, p.orderId())).isEqualByComparingTo("10.50");
            assertThat(creditNoteRows(p.orderId())).isZero();

            // Once the supplier's GSTIN is filled in, generating the invoice issues the waiting credit note too.
            String gstin = freshGstin();
            jdbc.update("update supplier_organization set gstin = ? where id = ?", gstin, seller.orgId());
            assertThat(post(seller.token(), generatePath(p), Map.of()).status()).isEqualTo(200);
            assertThat(creditNoteRows(p.orderId())).isEqualTo(1);
        }
    }

    // ── exports ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("exports")
    class Exports {

        @Test
        @DisplayName("the exports are marked unverified drafts; a buyer without a GSTIN is B2CS, one row per rate")
        void exportsAreDrafts() throws Exception {
            var seller = newSeller(freshGstin(), "0207");
            var p = placeNew(seller);
            readyAt(p, "9.6");
            post(seller.token(), generatePath(p), Map.of());
            String base = "/api/v1/supplier-orders/" + p.orderId() + "/tax-invoice";

            var csv = mvc.perform(MockMvcRequestBuilders.get(base + "/gstr1-csv")
                    .header("Authorization", "Bearer " + seller.token())).andReturn().getResponse();
            var xml = mvc.perform(MockMvcRequestBuilders.get(base + "/tally-xml")
                    .header("Authorization", "Bearer " + seller.token())).andReturn().getResponse();

            assertThat(csv.getStatus()).isEqualTo(200);
            assertThat(csv.getHeader("X-Export-Status")).isEqualTo("UNVERIFIED-DRAFT");
            assertThat(csv.getContentAsString()).startsWith("Type,Place Of Supply,");
            assertThat(csv.getContentAsString().strip().lines().count()).isEqualTo(2);   // header + one rate
            assertThat(csv.getContentAsString()).contains("OE,\"36-Telangana\",,5,960.0000");
            assertThat(xml.getHeader("X-Export-Status")).isEqualTo("UNVERIFIED-DRAFT");
            assertThat(xml.getContentAsString()).contains("UNVERIFIED DRAFT");
        }
    }
}
