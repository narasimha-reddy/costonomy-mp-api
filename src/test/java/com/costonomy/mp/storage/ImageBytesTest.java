package com.costonomy.mp.storage;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.storage.service.ImageBytes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What an upload is, as distinct from what it claims to be.
 *
 * <p>The client's {@code Content-Type} on a multipart part is whatever the client
 * put there and the filename extension is worse. Everything here reads the bytes.
 */
class ImageBytesTest {

    private static byte[] with(int... prefix) {
        byte[] out = new byte[64];
        for (int i = 0; i < prefix.length; i++) {
            out[i] = (byte) prefix[i];
        }
        return out;
    }

    @Test
    @DisplayName("identifies JPEG, PNG and WebP from their own bytes")
    void identifiesRealImages() {
        assertThat(ImageBytes.identify(with(0xFF, 0xD8, 0xFF)).contentType()).isEqualTo("image/jpeg");
        assertThat(ImageBytes.identify(with(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))
                .contentType()).isEqualTo("image/png");
        assertThat(ImageBytes.identify(
                with('R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'))
                .contentType()).isEqualTo("image/webp");
    }

    @Test
    @DisplayName("an HTML file named .jpg is refused")
    void refusesAMislabelledFile() {
        // The whole reason the check exists. Stored and served back from our own
        // origin, this is a script running as us.
        byte[] html = "<html><script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ImageBytes.identify(html))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not a JPEG, PNG or WebP");
    }

    @Test
    @DisplayName("an SVG is refused even though it is an image")
    void refusesSvg() {
        // Deliberately excluded rather than overlooked: an SVG is a document that
        // executes script, and it would be served from our origin.
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>"
                .getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ImageBytes.identify(svg))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("refuses an empty file and one over the size limit")
    void refusesEmptyAndOversized() {
        assertThatThrownBy(() -> ImageBytes.identify(new byte[0]))
                .isInstanceOf(BusinessException.class).hasMessageContaining("empty");

        byte[] huge = new byte[ImageBytes.MAX_BYTES + 1];
        huge[0] = (byte) 0xFF; huge[1] = (byte) 0xD8; huge[2] = (byte) 0xFF;
        assertThatThrownBy(() -> ImageBytes.identify(huge))
                .isInstanceOf(BusinessException.class).hasMessageContaining("5 MB");
    }

    @Test
    @DisplayName("size is checked before content, so a huge file is cheap to reject")
    void sizeBeforeContent() {
        byte[] huge = new byte[ImageBytes.MAX_BYTES + 1];   // not an image either
        assertThatThrownBy(() -> ImageBytes.identify(huge))
                .hasMessageContaining("5 MB");
    }
}
