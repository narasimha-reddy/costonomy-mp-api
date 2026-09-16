package com.costonomy.mp.storage.service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Where a file lives in the bucket.
 *
 * <p><b>The tenant is the first path segment, always.</b> A bucket laid out by
 * kind — {@code images/}, {@code documents/} — is one nobody can reason about
 * later: "delete everything belonging to this supplier" becomes a full scan, an
 * IAM policy cannot be written per tenant, and a listing leaks every tenant's
 * filenames to anyone who can list a prefix. Laid out by owner, all three are a
 * prefix operation.
 *
 * <p>The date segment below that is for lifecycle rules, not for humans: it is
 * what lets an expiry policy be written against old objects without reading any
 * of them.
 *
 * <p>The filename is a {@link UUID}, never the client's. An uploaded name is
 * attacker-controlled, collides across tenants, and leaks whatever the person
 * happened to call the file.
 */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    private static final DateTimeFormatter MONTH =
            DateTimeFormatter.ofPattern("yyyy/MM", Locale.ROOT).withZone(ZoneOffset.UTC);

    /**
     * A SKU photograph, owned by the supplier organisation that sells it.
     *
     * <p>{@code suppliers/{orgId}/stores/{storeId}/sku-images/{yyyy}/{MM}/{uuid}.{ext}}
     */
    public static String supplierSkuImage(long supplierOrganizationId, long storeId,
                                          String extension, Instant now) {
        return "suppliers/%d/stores/%d/sku-images/%s/%s.%s".formatted(
                supplierOrganizationId, storeId, MONTH.format(now), UUID.randomUUID(),
                normalizeExtension(extension));
    }

    /**
     * A restaurant-side file, owned by the restaurant rather than the outlet.
     *
     * <p>Outlets come and go; the restaurant is the tenant whose data has to be
     * exportable and deletable as one thing. The outlet is a segment below it so
     * a per-outlet listing is still a prefix.
     */
    public static String restaurantFile(long restaurantId, long outletId, String purpose,
                                        String extension, Instant now) {
        return "restaurants/%d/outlets/%d/%s/%s/%s.%s".formatted(
                restaurantId, outletId, slug(purpose), MONTH.format(now), UUID.randomUUID(),
                normalizeExtension(extension));
    }

    private static String slug(String value) {
        String cleaned = value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return cleaned.isEmpty() ? "files" : cleaned;
    }

    private static String normalizeExtension(String extension) {
        String cleaned = extension == null ? "" : extension.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
        return cleaned.isEmpty() ? "bin" : cleaned;
    }
}
