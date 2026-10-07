package com.costonomy.mp.credit;

import com.costonomy.mp.credit.web.dto.CreditDtos.SupplierPaymentPreviewRequest;
import com.costonomy.mp.credit.web.dto.CreditDtos.SupplierPaymentRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The shape rules of the supplier's receipt request: amount, method, reference. Dates need the clock and are the service's. */
class CreditSupplierPaymentRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final LocalDate DAY = LocalDate.of(2026, 3, 10);

    private static SupplierPaymentRequest request(String amount, String method, String reference) {
        return new SupplierPaymentRequest(amount == null ? null : new BigDecimal(amount), method, reference, DAY, null,
                null, null);
    }

    private static boolean valid(SupplierPaymentRequest request) {
        return VALIDATOR.validate(request).isEmpty();
    }

    @Test
    @DisplayName("amount: more than zero, at most two decimals")
    void amount() {
        assertThat(valid(request("0.01", "CASH", null))).isTrue();
        assertThat(valid(request("2500", "CASH", null))).isTrue();
        assertThat(valid(request("2500.50", "CASH", null))).isTrue();
        for (String bad : new String[]{"0", "0.00", "-1", "1.005", "0.001", null}) {
            assertThat(valid(request(bad, "CASH", null))).describedAs("amount " + bad).isFalse();
        }
    }

    @Test
    @DisplayName("method: the five, never ADJUSTMENT, WALLET, lower case or missing")
    void method() {
        for (String ok : new String[]{"CASH", "UPI", "BANK_TRANSFER", "CHEQUE", "CARD"}) {
            assertThat(valid(request("1.00", ok, "REF1234"))).describedAs(ok).isTrue();
        }
        for (String bad : new String[]{"ADJUSTMENT", "WALLET", "cash", "", null}) {
            assertThat(valid(request("1.00", bad, "REF1234"))).describedAs("method " + bad).isFalse();
        }
    }

    @Test
    @DisplayName("reference: UPI, bank transfer and cheque need 4 to 64 characters after trimming; cash and card may leave it out")
    void reference() {
        for (String method : new String[]{"UPI", "BANK_TRANSFER", "CHEQUE"}) {
            assertThat(valid(request("1.00", method, null))).describedAs(method + " null").isFalse();
            assertThat(valid(request("1.00", method, ""))).isFalse();
            assertThat(valid(request("1.00", method, "      "))).isFalse();
            assertThat(valid(request("1.00", method, "abc"))).isFalse();
            assertThat(valid(request("1.00", method, "  abc  "))).isFalse();
            assertThat(valid(request("1.00", method, "abcd"))).isTrue();
            assertThat(valid(request("1.00", method, "  abcd  "))).isTrue();
            assertThat(valid(request("1.00", method, "x".repeat(64)))).isTrue();
            assertThat(valid(request("1.00", method, "x".repeat(65)))).isFalse();
        }
        for (String method : new String[]{"CASH", "CARD"}) {
            assertThat(valid(request("1.00", method, null))).isTrue();
            assertThat(valid(request("1.00", method, "   "))).isTrue();
            assertThat(valid(request("1.00", method, "x".repeat(65)))).isFalse();
        }
    }

    @Test
    @DisplayName("the reference is carried trimmed, and blank is none")
    void trimmed() {
        assertThat(request("1.00", "UPI", "  UTR1234  ").trimmedReference()).isEqualTo("UTR1234");
        assertThat(request("1.00", "CASH", "   ").trimmedReference()).isNull();
        assertThat(request("1.00", "CASH", null).trimmedReference()).isNull();
    }

    @Test
    @DisplayName("invoice ids: not empty when given, each once; the preview takes the same amount and ids rule")
    void invoiceIds() {
        assertThat(VALIDATOR.validate(new SupplierPaymentRequest(new BigDecimal("1.00"), "CASH", null, DAY, null,
                List.of(), null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new SupplierPaymentRequest(new BigDecimal("1.00"), "CASH", null, DAY, null,
                List.of(1L, 1L), null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new SupplierPaymentRequest(new BigDecimal("1.00"), "CASH", null, DAY, null,
                List.of(2L, 1L), null))).isEmpty();
        assertThat(VALIDATOR.validate(new SupplierPaymentPreviewRequest(new BigDecimal("1.001"), null))).isNotEmpty();
        assertThat(VALIDATOR.validate(new SupplierPaymentPreviewRequest(new BigDecimal("1.00"), List.of(3L, 3L)))).isNotEmpty();
        assertThat(VALIDATOR.validate(new SupplierPaymentPreviewRequest(new BigDecimal("0.01"), List.of(3L)))).isEmpty();
    }
}
