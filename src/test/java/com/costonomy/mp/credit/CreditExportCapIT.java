package com.costonomy.mp.credit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The export row cap (B13, D-146), lowered to 2 for the test: beyond it the export is refused, never cut short. */
@TestPropertySource(properties = "costonomy.mp.credit.export-max-rows=2")
class CreditExportCapIT extends CreditClockedIT {

    @Test
    @DisplayName("more rows than the cap is 413 CREDIT_EXPORT_TOO_LARGE with max and rows, and no audit row; exactly the cap is fine")
    void rowCap() throws Exception {
        var line = s.creditLine("200000");
        long invoice = s.invoice(line, "65", 100);
        long other = s.invoice(line, "10", 100);
        for (int i = 0; i < 3; i++) {
            var paid = e.call("POST", line.seller().token(), "/api/v1/credit/invoices/" + invoice + "/payments",
                    UUID.randomUUID().toString(), Map.of("amount", "100.00", "method", "CASH", "reference", "R" + i));
            assertThat(paid.status()).isEqualTo(200);
        }
        long store = line.seller().storeId();
        String token = line.seller().token();

        var collections = CreditCsvSupport.get(mvc, token, "/api/v1/supplier-stores/" + store + "/credit/collections.csv");
        assertThat(collections.status()).isEqualTo(413);
        assertThat(collections.text()).contains("CREDIT_EXPORT_TOO_LARGE").contains("\"max\":2").contains("\"rows\":3");

        // The statement has two orders and three payments: five lines.
        var statement = CreditCsvSupport.get(mvc, token, "/api/v1/credit/agreements/" + line.agreementId()
                + "/statement.csv");
        assertThat(statement.status()).isEqualTo(413);
        assertThat(statement.text()).contains("CREDIT_EXPORT_TOO_LARGE");
        assertThat(exports("SUPPLIER_STORE", store)).isZero();
        assertThat(exports("CREDIT_AGREEMENT", line.agreementId())).isZero();

        // Exactly the cap: two payments in the window.
        jdbc.update("update credit_payment set paid_at = date_sub(paid_at, interval 40 day) where credit_invoice_id = ? "
                + "and reference = 'R0'", invoice);
        var two = CreditCsvSupport.get(mvc, token, "/api/v1/supplier-stores/" + store
                + "/credit/collections.csv?from=" + today().minusDays(5));
        assertThat(two.status()).isEqualTo(200);
        assertThat(two.rows()).hasSize(3);
        assertThat(exports("SUPPLIER_STORE", store)).isEqualTo(1);
        assertThat(other).isPositive();
    }
}
