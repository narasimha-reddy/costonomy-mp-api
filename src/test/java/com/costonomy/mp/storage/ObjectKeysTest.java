package com.costonomy.mp.storage;

import com.costonomy.mp.storage.service.ObjectKeys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The bucket layout, which is an access-control decision as much as a filing one.
 */
class ObjectKeysTest {

    private static final Instant MARCH = Instant.parse("2026-03-09T10:15:30Z");

    @Test
    @DisplayName("the tenant is the first segment, so a prefix is a tenant")
    void tenantLeads() {
        // This is what makes "delete everything for this supplier" a prefix
        // operation, lets an IAM policy be written per tenant, and stops a
        // listing of one prefix revealing another tenant's filenames.
        assertThat(ObjectKeys.supplierSkuImage(7, 12, "jpg", MARCH))
                .startsWith("suppliers/7/stores/12/sku-images/2026/03/");
        assertThat(ObjectKeys.restaurantFile(3, 9, "Receiving photo", "png", MARCH))
                .startsWith("restaurants/3/outlets/9/receiving-photo/2026/03/");
    }

    @Test
    @DisplayName("the filename is ours, never the uploader's")
    void filenameIsGenerated() {
        String a = ObjectKeys.supplierSkuImage(7, 12, "jpg", MARCH);
        String b = ObjectKeys.supplierSkuImage(7, 12, "jpg", MARCH);

        // An uploaded name is attacker-controlled, collides across tenants, and
        // leaks whatever the person called the file. Two uploads of the same
        // thing must not be able to overwrite each other either.
        assertThat(a).isNotEqualTo(b);
        assertThat(a).endsWith(".jpg");
    }

    @Test
    @DisplayName("an extension cannot introduce path separators or dots")
    void extensionIsSanitised() {
        // Belt and braces: the extension comes from our own sniffing today, but a
        // key builder that can be made to emit "../" is a key builder that will be.
        assertThat(ObjectKeys.supplierSkuImage(1, 1, "../../etc/passwd", MARCH))
                .doesNotContain("..").doesNotContain("etc/passwd");
        assertThat(ObjectKeys.supplierSkuImage(1, 1, null, MARCH)).endsWith(".bin");
    }

    @Test
    @DisplayName("a purpose cannot introduce path separators either")
    void purposeIsSlugged() {
        assertThat(ObjectKeys.restaurantFile(1, 1, "../../secrets", "png", MARCH))
                .doesNotContain("..")
                .startsWith("restaurants/1/outlets/1/secrets/");
    }
}
