package com.costonomy.mp.access.domain;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Marketplace's permissions and seeded roles (D-208): the codes doc 03 §14 defined and the roles that held them, as
 * the role / permission / role_permission tables had them at V105. They are published to costonomy-common-api at
 * start-up ({@code MarketplaceCatalogPublisher}), where businesses hold common's roles; nothing in Marketplace grants
 * them any more. Read once from {@code marketplace-catalog.json}.
 */
public final class MarketplaceCatalog {

    /** {@code scope}: RESTAURANT, SUPPLIER, INTERNAL (Costonomy staff) or SHARED. */
    public record SeededPermission(String code, String scope, String name, String description) {
    }

    public record SeededRole(String code, String scope, String name, String description, List<String> permissions) {
    }

    private record File(List<SeededPermission> permissions, List<SeededRole> roles) {
    }

    private static final File FILE = load();
    private static final Map<String, SeededRole> ROLES = new LinkedHashMap<>();

    static {
        FILE.roles().forEach(r -> ROLES.put(r.code(), r));
    }

    private MarketplaceCatalog() {
    }

    public static List<SeededPermission> permissions() {
        return FILE.permissions();
    }

    public static List<SeededRole> roles() {
        return FILE.roles();
    }

    public static Optional<SeededRole> role(String code) {
        return Optional.ofNullable(code == null ? null : ROLES.get(code));
    }

    private static File load() {
        try (InputStream in = MarketplaceCatalog.class.getResourceAsStream("/marketplace-catalog.json")) {
            if (in == null) {
                throw new IllegalStateException("marketplace-catalog.json is missing from the classpath");
            }
            return new ObjectMapper().configure(
                    com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                    .readValue(in, File.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
