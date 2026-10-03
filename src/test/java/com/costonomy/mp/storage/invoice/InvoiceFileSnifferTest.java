package com.costonomy.mp.storage.invoice;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InvoiceFileSnifferTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length + 16];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Test
    @DisplayName("JPEG, PNG, WebP and PDF are known by their first bytes")
    void knownFormats() {
        assertThat(InvoiceFileSniffer.identify(bytes(0xFF, 0xD8, 0xFF, 0xE0)).contentType()).isEqualTo("image/jpeg");
        assertThat(InvoiceFileSniffer.identify(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)).extension())
                .isEqualTo("png");
        var webp = bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P');
        assertThat(InvoiceFileSniffer.identify(webp).contentType()).isEqualTo("image/webp");
        var pdf = InvoiceFileSniffer.identify("%PDF-1.7\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(pdf.contentType()).isEqualTo("application/pdf");
        assertThat(pdf.extension()).isEqualTo("pdf");
    }

    @Test
    @DisplayName("anything else is refused as an unsupported type, whatever it is called")
    void unknownRefused() {
        for (byte[] content : new byte[][]{
                "<svg xmlns='http://www.w3.org/2000/svg'/>".getBytes(StandardCharsets.UTF_8),
                "GIF89a....".getBytes(StandardCharsets.US_ASCII),
                "MZ executable".getBytes(StandardCharsets.US_ASCII),
                " %PDF-1.4".getBytes(StandardCharsets.US_ASCII),
                bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E')}) {
            assertThatThrownBy(() -> InvoiceFileSniffer.identify(content))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.code()).isEqualTo(ErrorCode.INVOICE_FILE_TYPE));
        }
    }

    @Test
    @DisplayName("an empty file and a file over 5 MB are refused")
    void sizeLimits() {
        assertThatThrownBy(() -> InvoiceFileSniffer.identify(new byte[0])).isInstanceOf(BusinessException.class);
        var big = new byte[InvoiceFileSniffer.MAX_BYTES + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertThatThrownBy(() -> InvoiceFileSniffer.identify(big))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        var exactly = new byte[InvoiceFileSniffer.MAX_BYTES];
        exactly[0] = (byte) 0xFF;
        exactly[1] = (byte) 0xD8;
        exactly[2] = (byte) 0xFF;
        assertThat(InvoiceFileSniffer.identify(exactly).extension()).isEqualTo("jpg");
    }
}
