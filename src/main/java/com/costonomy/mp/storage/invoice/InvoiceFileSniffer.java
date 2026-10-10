package com.costonomy.mp.storage.invoice;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

/**
 * What an uploaded bill page really is, decided from its first bytes. The declared Content-Type and
 * the file name are never consulted. JPEG, PNG, WebP and PDF only.
 */
public final class InvoiceFileSniffer {

    private InvoiceFileSniffer() {
    }

    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public record Kind(String contentType, String extension) {
    }

    public static Kind identify(byte[] c) {
        if (c == null || c.length == 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "One of the files is empty.");
        }
        if (c.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "Each file must be 5 MB or smaller. Please choose a smaller one.");
        }
        if (starts(c, 0xFF, 0xD8, 0xFF)) {
            return new Kind("image/jpeg", "jpg");
        }
        if (starts(c, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return new Kind("image/png", "png");
        }
        if (starts(c, 'R', 'I', 'F', 'F') && c.length > 11
                && c[8] == 'W' && c[9] == 'E' && c[10] == 'B' && c[11] == 'P') {
            return new Kind("image/webp", "webp");
        }
        if (starts(c, '%', 'P', 'D', 'F', '-')) {
            return new Kind("application/pdf", "pdf");
        }
        throw new BusinessException(ErrorCode.INVOICE_FILE_TYPE);
    }

    private static boolean starts(byte[] c, int... prefix) {
        if (c.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((c[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
