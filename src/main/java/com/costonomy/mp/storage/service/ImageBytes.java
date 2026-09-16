package com.costonomy.mp.storage.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;

import java.util.Locale;

/**
 * What an uploaded file actually is, as opposed to what it claims to be.
 *
 * <p>A browser's {@code Content-Type} on a multipart part is whatever the client
 * put there, and the filename extension is worse. Neither is evidence. So the
 * type is decided by reading the first bytes, and the declared values are used
 * for nothing except the error message.
 *
 * <p>Only three formats are accepted, and the list is short on purpose: SVG is
 * excluded because it is a document that executes script, and anything we cannot
 * identify from its own bytes is refused rather than stored and served back.
 */
public final class ImageBytes {

    private ImageBytes() {
    }

    /** Big enough for a photograph off a phone, small enough to refuse a video. */
    public static final int MAX_BYTES = 5 * 1024 * 1024;

    public record Kind(String contentType, String extension) {
    }

    public static Kind identify(byte[] content) {
        if (content == null || content.length == 0) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST, "That file is empty.");
        }
        if (content.length > MAX_BYTES) {
            throw new BusinessException(ErrorCode.MALFORMED_REQUEST,
                    "That image is larger than 5 MB. Please choose a smaller one.");
        }

        if (startsWith(content, 0xFF, 0xD8, 0xFF)) {
            return new Kind("image/jpeg", "jpg");
        }
        if (startsWith(content, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return new Kind("image/png", "png");
        }
        // RIFF....WEBP — the size field sits between the two markers.
        if (startsWith(content, 'R', 'I', 'F', 'F') && content.length > 11
                && content[8] == 'W' && content[9] == 'E' && content[10] == 'B'
                && content[11] == 'P') {
            return new Kind("image/webp", "webp");
        }

        throw new BusinessException(ErrorCode.MALFORMED_REQUEST,
                "That file is not a JPEG, PNG or WebP image.");
    }

    /** Only for the error message; never for deciding what was uploaded. */
    public static String describe(String declaredContentType) {
        return declaredContentType == null ? "unknown"
                : declaredContentType.toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] content, int... prefix) {
        if (content.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((content[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }
}
