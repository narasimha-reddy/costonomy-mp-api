package com.costonomy.mp.storage.invoice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceKeysTest {

    @Test
    @DisplayName("invoices/<outlet>/<yyyy-MM>/<uuid>-p<page>.<ext>")
    void layout() {
        var id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        assertThat(InvoiceKeys.forPage(42, YearMonth.of(2026, 9), id, 2, "jpg"))
                .isEqualTo("invoices/42/2026-09/123e4567-e89b-12d3-a456-426614174000-p2.jpg");
    }

    @Test
    @DisplayName("an extension with a path or a dot in it, or a page below 1, is refused")
    void nothingFromAClientGetsIn() {
        var id = UUID.randomUUID();
        for (String ext : new String[]{"../x", "jpg/../../etc", "a.b", "", "JPG", "toolongext"}) {
            assertThatThrownBy(() -> InvoiceKeys.forPage(1, YearMonth.of(2026, 9), id, 1, ext))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> InvoiceKeys.forPage(1, YearMonth.of(2026, 9), id, 0, "jpg"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
