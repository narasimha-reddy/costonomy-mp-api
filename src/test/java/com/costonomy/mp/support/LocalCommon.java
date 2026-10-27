package com.costonomy.mp.support;

import com.costonomy.mp.access.domain.MarketplaceCatalog;
import com.costonomy.mp.access.domain.PermissionCodes;
import com.costonomy.mp.billing.service.GstState;
import com.costonomy.mp.identity.common.MarketplaceCatalogPublisher;
import com.costonomy.mp.identity.service.PhoneNumbers;
import com.costonomy.mp.supplier.domain.OperatingHours;
import com.costonomy.mp.supplier.domain.OperatingHoursCodec;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.CostonomySecurityProperties;
import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import com.costonomy.security.client.CommonApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jwt.SignedJWT;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * costonomy-common-api for the integration tests, in-process and in memory (D-208).
 *
 * <p>Marketplace keeps no copies of common's data, so the stand-in holds them: people, businesses (restaurants and
 * suppliers), their outlets and stores, Marketplace's outlet settings, role grants and push devices. It answers
 * common's access lookups (D-75: a role per scope, the seeded roles of {@link MarketplaceCatalog} with their codes
 * mapped by {@link PermissionCodes}) and directory queries the way common does, and the tests change it the way the
 * app and Costonomy staff would change common: through {@link ApiClient}, which sends the identity calls the app now
 * makes to common here, and through the static helpers below (verify, activate, grant, settings ...).
 *
 * <p>State lives for the JVM, like the test database it sits beside; tests use fresh phones and businesses.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalCommon {

    private static final AtomicLong VERSION = new AtomicLong();
    private static final AtomicLong USER_IDS = new AtomicLong(10_000);
    private static final AtomicLong ORG_IDS = new AtomicLong(10_000);
    private static final AtomicLong OUTLET_IDS = new AtomicLong(10_000);
    private static final AtomicLong DEVICE_IDS = new AtomicLong(10_000);
    private static final AtomicLong VERIFICATION_IDS = new AtomicLong(10_000);
    private static final ObjectMapper JSON = new ObjectMapper();

    static final Map<Long, User> USERS = new ConcurrentHashMap<>();
    static final Map<String, Long> BY_PHONE = new ConcurrentHashMap<>();
    static final Map<Long, Org> ORGS = new ConcurrentHashMap<>();
    static final Map<Long, Place> PLACES = new ConcurrentHashMap<>();
    static final List<Grant> GRANTS = new CopyOnWriteArrayList<>();
    static final List<CommonApiClient.PushDevice> DEVICES = new CopyOnWriteArrayList<>();
    /** Roles a test made for itself (a business's own role in common), by code. */
    static final Map<String, MarketplaceCatalog.SeededRole> CUSTOM_ROLES = new ConcurrentHashMap<>();
    /** Verification request id → business. */
    static final Map<Long, Long> VERIFICATIONS = new ConcurrentHashMap<>();
    /** Common's MARKETPLACE defaults (its V24). */
    static final Map<String, Object> DEFAULTS = Map.of(SupplierStanding.PREPARATION_MINUTES, 60,
            SupplierStanding.ONLINE, true, SupplierStanding.RESPONSE_SLA_SECONDS, 60,
            SupplierStanding.DIRECT_ORDERS_ENABLED, false, SupplierStanding.CHAT_ENABLED, true);

    static final class User {
        long id;
        String phone;
        String name;
        String email;
        volatile String status = "ACTIVE";
    }

    static final class Org {
        long id;
        String orgType;
        String displayName;
        String legalName;
        String gstin;
        String contactName;
        String contactPhone;
        String contactEmail;
        volatile String claimStatus = "CLAIMED";
        volatile String verificationStatus = "UNVERIFIED";
        volatile String status = "ACTIVE";
        volatile String enrollmentStatus = "ACTIVE";
        volatile boolean live;
        Instant createdAt = Instant.now();
        Long createdBy;
    }

    static final class Place {
        long id;
        long orgId;
        String kind;
        String name;
        String addressLine1;
        String addressLine2;
        String landmark;
        String city;
        String stateCode;
        String pincode;
        BigDecimal latitude;
        BigDecimal longitude;
        String googlePlaceId;
        String formattedAddress;
        String contactName;
        String contactPhone;
        volatile String status = "ACTIVE";
        volatile Map<String, Object> hours;
        final Map<String, Object> settings = new ConcurrentHashMap<>();
        Instant createdAt = Instant.now();
    }

    /** {@code scopeType}: RESTAURANT / SUPPLIER (business-wide), OUTLET / SUPPLIER_STORE, or PLATFORM. */
    record Grant(long userId, String role, String scopeType, Long scopeId) {
    }

    // ── beans ──────────────────────────────────────────────────────────────

    @Bean
    @Primary
    AccessClient localAccessClient() {
        return new AccessClient(null, new ObjectMapper(), properties(), Clock.systemUTC()) {
            @Override
            public Optional<AccessSnapshot> get(long userId, String sid) {
                return Optional.ofNullable(snapshot(userId));
            }
        };
    }

    @Bean
    @Primary
    CommonApiClient localCommonApiClient() {
        return new CommonApiClient(null, new ObjectMapper(), properties(), Clock.systemUTC()) {
            @Override
            public Optional<CommonUser> user(long id) {
                return Optional.ofNullable(USERS.get(id)).map(LocalCommon::commonUser);
            }

            @Override
            public List<CommonUser> users(Collection<Long> ids) {
                return ids.stream().distinct().map(USERS::get).filter(java.util.Objects::nonNull)
                        .map(LocalCommon::commonUser).toList();
            }

            @Override
            public List<CommonMember> members(long orgId, Long outletId, String permission, Integer audience) {
                return LocalCommon.members(orgId, outletId, permission, audience);
            }

            @Override
            public List<CommonDevice> devices(long userId) {
                return DEVICES.stream().filter(d -> d.userId() == userId).map(d -> new CommonDevice(d.deviceId(),
                        d.platform(), d.pushProvider(), d.pushToken(), d.appVersion(), null)).toList();
            }

            @Override
            public List<PushDevice> pushDevices(Collection<Long> userIds) {
                Set<Long> wanted = Set.copyOf(userIds);
                return DEVICES.stream().filter(d -> wanted.contains(d.userId())).toList();
            }

            @Override
            public void invalidPushToken(String pushToken) {
                DEVICES.removeIf(d -> d.pushToken().equals(pushToken));
            }

            @Override
            public List<AppOutlet> appOutlets(OutletQuery query) {
                return LocalCommon.appOutlets(query);
            }

            @Override
            public OrgPage appOrgs(OrgQuery query) {
                return LocalCommon.appOrgs(query);
            }

            @Override
            public Map<String, Object> putOutletSettings(long outletId, Map<String, Object> values,
                                                         Collection<String> remove, Long actingUserId) {
                Place p = place(outletId);
                if (values != null) {
                    p.settings.putAll(values);
                }
                if (remove != null) {
                    remove.forEach(p.settings::remove);
                }
                return effectiveSettings(p);
            }

            @Override
            public CommonEnrollment setEnrollment(long orgId, String status, Boolean live, Long actingUserId) {
                Org o = LocalCommon.org(orgId);
                if (status != null) {
                    o.enrollmentStatus = status;
                }
                if (live != null) {
                    o.live = live;
                }
                VERSION.incrementAndGet();
                return new CommonEnrollment(orgId, orgId, null, "MARKETPLACE", o.enrollmentStatus, null, o.live);
            }
        };
    }

    private static CostonomySecurityProperties properties() {
        CostonomySecurityProperties p = new CostonomySecurityProperties();
        p.setApp("MARKETPLACE");
        return p;
    }

    // ── people and sign-in ─────────────────────────────────────────────────

    /** Phone + code sign-in, as mp-mobile does it against common: the account is created on first use. */
    public static String signIn(String rawPhone) {
        return TestKeys.accessToken(userIdFor(PhoneNumbers.normalize(rawPhone), null));
    }

    /** The user a test token stands for. */
    public static long userIdOf(String token) {
        try {
            return Long.parseLong(SignedJWT.parse(token).getJWTClaimsSet().getSubject());
        } catch (Exception e) {
            throw new IllegalArgumentException("Not a test token", e);
        }
    }

    /** The user with this phone (any format), created if new. */
    public static long userId(String rawPhone) {
        return userIdFor(PhoneNumbers.normalize(rawPhone), null);
    }

    static long userIdFor(String e164, String name) {
        return BY_PHONE.computeIfAbsent(e164, phone -> {
            User u = new User();
            u.id = USER_IDS.incrementAndGet();
            u.phone = phone;
            u.name = name;
            USERS.put(u.id, u);
            return u.id;
        });
    }

    public static String phoneOf(long userId) {
        return USERS.get(userId).phone;
    }

    public static void setUserName(long userId, String name) {
        USERS.get(userId).name = name;
    }

    /** ACTIVE, SUSPENDED or DELETED, as common's admin would set it. */
    public static void setUserStatus(long userId, String status) {
        USERS.get(userId).status = status;
        VERSION.incrementAndGet();
    }

    public static void registerDevice(long userId, String platform, String pushToken) {
        DEVICES.removeIf(d -> d.pushToken().equals(pushToken));
        DEVICES.add(new CommonApiClient.PushDevice(userId, DEVICE_IDS.incrementAndGet(), platform, "FCM", pushToken,
                "1.0.0"));
    }

    // ── businesses, outlets and stores (what the app does in common) ────────

    /** A restaurant as the app's onboarding creates it: business, owner, first outlet when given. */
    public static Org createRestaurant(long ownerId, Map<String, Object> in) {
        Org o = newOrg("RESTAURANT", ownerId, str(in, "name"), str(in, "legalName"), str(in, "gstin"), null, null,
                null);
        grant(ownerId, "REST_OWNER", "RESTAURANT", o.id);
        Map<String, Object> first = map(in.get("firstOutlet"));
        if (first != null) {
            createPlace(o.id, "OUTLET", first);
        }
        return o;
    }

    public static Org createSupplier(long ownerId, Map<String, Object> in) {
        Org o = newOrg("SUPPLIER", ownerId, str(in, "displayName"), str(in, "legalName"), str(in, "gstin"),
                str(in, "contactName"), str(in, "contactPhone"), str(in, "contactEmail"));
        grant(ownerId, "SUP_OWNER", "SUPPLIER", o.id);
        Map<String, Object> first = map(in.get("firstStore"));
        if (first != null) {
            createPlace(o.id, "STORE", first);
        }
        return o;
    }

    private static Org newOrg(String type, long ownerId, String displayName, String legalName, String gstin,
                              String contactName, String contactPhone, String contactEmail) {
        Org o = new Org();
        o.id = ORG_IDS.incrementAndGet();
        o.orgType = type;
        o.displayName = displayName == null ? legalName : displayName;
        o.legalName = legalName;
        o.gstin = gstin == null || gstin.isBlank() ? null : gstin.trim().toUpperCase(Locale.ROOT);
        o.contactName = contactName;
        o.contactPhone = contactPhone;
        o.contactEmail = contactEmail;
        o.createdBy = ownerId;
        ORGS.put(o.id, o);
        return o;
    }

    public static Place createPlace(long orgId, String kind, Map<String, Object> in) {
        org(orgId);
        Place p = new Place();
        p.id = OUTLET_IDS.incrementAndGet();
        p.orgId = orgId;
        p.kind = kind;
        apply(p, in);
        PLACES.put(p.id, p);
        VERSION.incrementAndGet();
        return p;
    }

    /** A supplier business with one store in Hyderabad and no people, for rows that only need a named store. */
    public static long supplierStore(String name) {
        Org o = newOrg("SUPPLIER", 0, name, name, null, null, null, null);
        return createPlace(o.id, "STORE", Map.of("name", name, "addressLine1", "MG Road", "city", "Hyderabad",
                "state", "Telangana")).id;
    }

    /** What the app's outlet and store edit screens change: common's fields and Marketplace's settings. */
    static void apply(Place p, Map<String, Object> in) {
        if (in.containsKey("name")) p.name = str(in, "name");
        if (in.containsKey("addressLine1")) p.addressLine1 = str(in, "addressLine1");
        if (in.containsKey("addressLine2")) p.addressLine2 = str(in, "addressLine2");
        if (in.containsKey("landmark")) p.landmark = str(in, "landmark");
        if (in.containsKey("city")) p.city = str(in, "city");
        if (in.containsKey("state")) p.stateCode = stateCode(str(in, "state"));
        if (in.containsKey("stateCode")) p.stateCode = stateCode(str(in, "stateCode"));
        if (in.containsKey("pincode")) p.pincode = str(in, "pincode");
        if (in.containsKey("latitude")) p.latitude = decimal(in.get("latitude"));
        if (in.containsKey("longitude")) p.longitude = decimal(in.get("longitude"));
        if (in.containsKey("googlePlaceId")) p.googlePlaceId = str(in, "googlePlaceId");
        if (in.containsKey("formattedAddress")) p.formattedAddress = str(in, "formattedAddress");
        if (in.containsKey("contactName")) p.contactName = str(in, "contactName");
        if (in.containsKey("contactPhone")) p.contactPhone = str(in, "contactPhone");
        if (in.get("operatingHours") instanceof Map<?, ?> h) {
            p.hours = commonHours(map(h));
        }
        if (in.containsKey("preparationMinutes") && in.get("preparationMinutes") != null) {
            p.settings.put(SupplierStanding.PREPARATION_MINUTES, ((Number) in.get("preparationMinutes")).intValue());
        }
        if (in.containsKey("responseSlaSeconds") && in.get("responseSlaSeconds") != null) {
            p.settings.put(SupplierStanding.RESPONSE_SLA_SECONDS, ((Number) in.get("responseSlaSeconds")).intValue());
        }
        if (in.containsKey("directOrdersEnabled") && in.get("directOrdersEnabled") != null) {
            p.settings.put(SupplierStanding.DIRECT_ORDERS_ENABLED, in.get("directOrdersEnabled"));
        }
        if (in.containsKey("deliveryInstructions")) {
            if (in.get("deliveryInstructions") == null) {
                p.settings.remove(SupplierStanding.DELIVERY_INSTRUCTIONS);
            } else {
                p.settings.put(SupplierStanding.DELIVERY_INSTRUCTIONS, in.get("deliveryInstructions"));
            }
        }
        if (in.get("status") instanceof String s) {
            setStatus(p, s);
        }
    }

    /** OFFLINE / ACTIVE is the store's own switch (the online setting); anything else is common's status. */
    static void setStatus(Place p, String status) {
        switch (status) {
            case "OFFLINE" -> p.settings.put(SupplierStanding.ONLINE, false);
            case "ACTIVE" -> {
                p.settings.remove(SupplierStanding.ONLINE);
                p.status = "ACTIVE";
            }
            default -> p.status = status;
        }
    }

    private static Map<String, Object> commonHours(Map<String, Object> h) {
        if (!h.containsKey("days") && !h.containsKey("opensAt")) {
            return h;
        }
        Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
        if (h.get("days") instanceof List<?> l) {
            l.forEach(d -> days.add(DayOfWeek.valueOf(String.valueOf(d).toUpperCase(Locale.ROOT))));
        }
        return OperatingHoursCodec.toCommon(new OperatingHours(days, time(h.get("opensAt")), time(h.get("closesAt"))));
    }

    private static LocalTime time(Object v) {
        return v == null ? null : LocalTime.parse(String.valueOf(v));
    }

    // ── what Costonomy staff and common's queues do ────────────────────────

    /** Verified and live: the supplier trades (what tests used to write into supplier_organization). */
    public static void activate(long supplierId) {
        Org o = org(supplierId);
        o.verificationStatus = "VERIFIED";
        o.enrollmentStatus = "ACTIVE";
        o.live = true;
        VERSION.incrementAndGet();
    }

    /** The GSTIN verified in common's queue; not yet activated. */
    public static void verify(long supplierId) {
        org(supplierId).verificationStatus = "VERIFIED";
    }

    /** A verification submitted and waiting (VERIFICATION_PENDING). Returns the request's id. */
    public static long submitVerification(long supplierId, String gstin) {
        Org o = org(supplierId);
        if (gstin != null) {
            o.gstin = gstin;
        }
        o.verificationStatus = "PENDING";
        long id = VERIFICATION_IDS.incrementAndGet();
        VERIFICATIONS.put(id, supplierId);
        return id;
    }

    public static void reject(long supplierId) {
        org(supplierId).verificationStatus = "REJECTED";
    }

    /** The business's Marketplace enrollment suspended (common's SUSPENDED). */
    public static void suspend(long orgId) {
        org(orgId).enrollmentStatus = "SUSPENDED";
        VERSION.incrementAndGet();
    }

    /** Sets a supplier straight to a lifecycle stage, the way tests used to write lifecycle_status. */
    public static void setLifecycle(long supplierId, String lifecycle) {
        Org o = org(supplierId);
        switch (lifecycle) {
            case "ACTIVE" -> activate(supplierId);
            case "VERIFIED" -> {
                o.verificationStatus = "VERIFIED";
                o.live = false;
                o.enrollmentStatus = "ACTIVE";
            }
            case "VERIFICATION_PENDING" -> {
                o.verificationStatus = "PENDING";
                o.live = false;
            }
            case "REGISTERED" -> {
                o.verificationStatus = "UNVERIFIED";
                o.live = false;
                o.enrollmentStatus = "ACTIVE";
            }
            case "SUSPENDED" -> suspend(supplierId);
            case "INVITED" -> o.claimStatus = "UNCLAIMED";
            default -> throw new IllegalArgumentException(lifecycle);
        }
    }

    public static void setGstin(long orgId, String gstin) {
        org(orgId).gstin = gstin;
    }

    public static void setLegalName(long orgId, String legalName) {
        org(orgId).legalName = legalName;
    }

    public static void setOrgName(long orgId, String name) {
        org(orgId).displayName = name;
    }

    /** A Marketplace setting on an outlet or store (null clears it back to the default). */
    public static void setting(long outletId, String key, Object value) {
        Place p = place(outletId);
        if (value == null) {
            p.settings.remove(key);
        } else {
            p.settings.put(key, value);
        }
    }

    /** Change outlet or store fields as the app's edit screen would ({@code status} OFFLINE / ACTIVE included). */
    public static void update(long outletId, Map<String, Object> fields) {
        apply(place(outletId), fields);
        VERSION.incrementAndGet();
    }

    /** Open around the clock, every day. */
    public static void openAroundTheClock(long storeId) {
        place(storeId).hours = OperatingHoursCodec.toCommon(new OperatingHours(EnumSet.allOf(DayOfWeek.class),
                LocalTime.MIDNIGHT, LocalTime.MIDNIGHT));
    }

    public static void setHours(long storeId, Map<String, Object> hours) {
        place(storeId).hours = commonHours(hours);
    }

    /** ACTIVE, INACTIVE, SUSPENDED or CLOSED in common; OFFLINE is the store's own switch. */
    public static void setStatus(long outletId, String status) {
        setStatus(place(outletId), status);
        VERSION.incrementAndGet();
    }

    public static long orgOf(long outletId) {
        return place(outletId).orgId;
    }

    public static List<Long> outletsOf(long orgId) {
        return PLACES.values().stream().filter(p -> p.orgId == orgId).map(p -> p.id).sorted().toList();
    }

    // ── grants ─────────────────────────────────────────────────────────────

    /** Grants a seeded role: {@code scopeType} RESTAURANT / SUPPLIER, OUTLET / SUPPLIER_STORE or PLATFORM. */
    public static void grant(long userId, String roleCode, String scopeType, Long scopeId) {
        if (role(roleCode).isEmpty()) {
            throw new IllegalArgumentException("Unknown role " + roleCode);
        }
        Grant g = new Grant(userId, roleCode, scopeType, "PLATFORM".equals(scopeType) ? null : scopeId);
        if (!GRANTS.contains(g)) {
            GRANTS.add(g);
        }
        VERSION.incrementAndGet();
    }

    public static void grantPlatform(long userId, String roleCode) {
        grant(userId, roleCode, "PLATFORM", null);
    }

    public static void revoke(long userId, String roleCode, String scopeType, Long scopeId) {
        GRANTS.remove(new Grant(userId, roleCode, scopeType, "PLATFORM".equals(scopeType) ? null : scopeId));
        VERSION.incrementAndGet();
    }

    /** Every grant of a user, everywhere (a removed member). */
    public static void revokeAll(long userId) {
        GRANTS.removeIf(g -> g.userId() == userId);
        VERSION.incrementAndGet();
    }

    /**
     * A business's own role, as an owner makes it in common: {@code scope} RESTAURANT or SUPPLIER, holding these
     * Marketplace permissions (the seeded codes, e.g. STORE_VIEW). Its code must start REST_ or SUP_.
     */
    public static void customRole(String code, String scope, String name, String... permissions) {
        CUSTOM_ROLES.put(code, new MarketplaceCatalog.SeededRole(code, scope, name, null, List.of(permissions)));
    }

    static Optional<MarketplaceCatalog.SeededRole> role(String code) {
        return Optional.ofNullable(CUSTOM_ROLES.get(code)).or(() -> MarketplaceCatalog.role(code));
    }

    /** The id common shows for a role (its position in the catalogue; a test's own roles after them). */
    public static long roleId(String roleCode) {
        List<MarketplaceCatalog.SeededRole> roles = allRoles();
        for (int i = 0; i < roles.size(); i++) {
            if (roles.get(i).code().equals(roleCode)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("Unknown role " + roleCode);
    }

    private static List<MarketplaceCatalog.SeededRole> allRoles() {
        List<MarketplaceCatalog.SeededRole> roles = new ArrayList<>(MarketplaceCatalog.roles());
        CUSTOM_ROLES.values().stream().sorted(Comparator.comparing(MarketplaceCatalog.SeededRole::code))
                .forEach(roles::add);
        return roles;
    }

    static String roleCode(long roleId) {
        List<MarketplaceCatalog.SeededRole> roles = allRoles();
        return roleId >= 1 && roleId <= roles.size() ? roles.get((int) roleId - 1).code() : null;
    }

    /** An invitation accepted at once: the person (created by phone if new) holds the role at the scope. */
    public static long invite(long orgId, String rawPhone, String country, String name, long roleId, Long outletId) {
        Org o = org(orgId);
        String role = roleCode(roleId);
        boolean supplier = "SUPPLIER".equals(o.orgType);
        if (role == null || (supplier ? !role.startsWith("SUP_") : !role.startsWith("REST_"))
                || (outletId != null && role.endsWith("_OWNER"))) {
            throw new IllegalArgumentException("Choose one of this business's roles");
        }
        long userId = userIdFor(PhoneNumbers.normalize(rawPhone, country), name);
        if (outletId == null) {
            grant(userId, role, supplier ? "SUPPLIER" : "RESTAURANT", orgId);
        } else {
            grant(userId, role, supplier ? "SUPPLIER_STORE" : "OUTLET", outletId);
        }
        return userId;
    }

    // ── access, as common resolves it ──────────────────────────────────────

    private static final class OrgAccess {
        final Org org;
        final List<String> businessRoles = new ArrayList<>();
        final Map<Long, List<String>> outletRoles = new LinkedHashMap<>();

        OrgAccess(Org org) {
            this.org = org;
        }
    }

    static AccessSnapshot snapshot(long userId) {
        User u = USERS.get(userId);
        if (u == null) {
            return null;
        }
        List<String> platformRoles = new ArrayList<>();
        Set<String> platformPermissions = new TreeSet<>();
        Map<Long, OrgAccess> orgs = new LinkedHashMap<>();
        for (Grant g : GRANTS) {
            if (g.userId() != userId) {
                continue;
            }
            switch (g.scopeType()) {
                case "RESTAURANT", "SUPPLIER" -> {
                    Org o = ORGS.get(g.scopeId());
                    if (o != null) {
                        orgs.computeIfAbsent(o.id, id -> new OrgAccess(o)).businessRoles.add(g.role());
                    }
                }
                case "OUTLET", "SUPPLIER_STORE" -> {
                    Place p = PLACES.get(g.scopeId());
                    Org o = p == null ? null : ORGS.get(p.orgId);
                    if (o != null) {
                        orgs.computeIfAbsent(o.id, id -> new OrgAccess(o)).outletRoles
                                .computeIfAbsent(p.id, id -> new ArrayList<>()).add(g.role());
                    }
                }
                default -> {
                    platformRoles.add(g.role());
                    platformPermissions.addAll(permissions(List.of(g.role())));
                }
            }
        }
        List<AccessSnapshot.Org> out = new ArrayList<>();
        for (OrgAccess a : orgs.values()) {
            // Common's enrollment rule: a business is in the app while it and its enrollment are active.
            if (!"ACTIVE".equals(a.org.status) || !Set.of("ACTIVE", "TRIAL").contains(a.org.enrollmentStatus)) {
                continue;
            }
            boolean business = !a.businessRoles.isEmpty();
            List<Long> outletIds = business ? outletsOf(a.org.id) : new ArrayList<>(a.outletRoles.keySet());
            List<AccessSnapshot.Outlet> outlets = new ArrayList<>();
            for (Long outletId : outletIds) {
                List<String> own = a.outletRoles.getOrDefault(outletId, List.of());
                List<String> roles = new ArrayList<>(a.businessRoles);
                roles.addAll(own);
                AccessSnapshot.Role shown = roleRef(!own.isEmpty() ? own.get(0) : a.businessRoles.get(0));
                outlets.add(new AccessSnapshot.Outlet(outletId, PLACES.get(outletId).status, shown, permissions(roles),
                        false, roles.stream().map(MarketplaceCatalogPublisher::level).min(Integer::compare).orElse(3),
                        null, true));
            }
            out.add(new AccessSnapshot.Org(a.org.id, a.org.orgType, "ACTIVE", business ? "BUSINESS" : "OUTLET", null,
                    business ? roleRef(a.businessRoles.get(0)) : null, permissions(a.businessRoles), outlets));
        }
        return new AccessSnapshot(userId, u.status, true, "MARKETPLACE",
                new AccessSnapshot.Platform(platformRoles, platformPermissions), out, null, VERSION.incrementAndGet());
    }

    static Set<String> permissions(List<String> roles) {
        Set<String> codes = new TreeSet<>();
        for (String role : roles) {
            role(role).ifPresent(r -> r.permissions().forEach(c -> {
                String code = PermissionCodes.of(c);
                if (code != null) {
                    codes.add(code);
                }
            }));
        }
        return codes;
    }

    /** A seeded role as common shows it: Owner and Admin are the built-in templates, the rest the business's own. */
    private static AccessSnapshot.Role roleRef(String code) {
        String name = role(code).map(MarketplaceCatalog.SeededRole::name).orElse(code);
        String template = code.endsWith("_OWNER") ? "OWNER" : code.endsWith("_ADMIN") ? "ADMIN" : null;
        return new AccessSnapshot.Role(roleId(code), MarketplaceCatalogPublisher.publishedName(code, name), template);
    }

    static List<CommonApiClient.CommonMember> members(long orgId, Long outletId, String permission, Integer audience) {
        Org o = ORGS.get(orgId);
        if (o == null) {
            return List.of();
        }
        boolean supplier = "SUPPLIER".equals(o.orgType);
        List<Long> outlets = outletsOf(orgId);
        Map<Long, List<String>> rolesByUser = new LinkedHashMap<>();
        Map<Long, Boolean> businessByUser = new LinkedHashMap<>();
        for (Grant g : GRANTS) {
            boolean business = g.scopeType().equals(supplier ? "SUPPLIER" : "RESTAURANT");
            boolean atOutlet = g.scopeType().equals(supplier ? "SUPPLIER_STORE" : "OUTLET");
            boolean applies = business ? g.scopeId() == orgId
                    : atOutlet && (outletId == null ? outlets.contains(g.scopeId()) : g.scopeId().equals(outletId));
            if (applies) {
                rolesByUser.computeIfAbsent(g.userId(), id -> new ArrayList<>()).add(g.role());
                businessByUser.merge(g.userId(), business, Boolean::logicalOr);
            }
        }
        List<CommonApiClient.CommonMember> members = new ArrayList<>();
        rolesByUser.forEach((userId, roles) -> {
            if (permission != null && !permissions(roles).contains(permission)) {
                return;
            }
            Set<Integer> levels = new TreeSet<>();
            roles.forEach(r -> levels.add(MarketplaceCatalogPublisher.level(r)));
            if (audience != null && levels.stream().noneMatch(l -> l <= audience)) {
                return;
            }
            User u = USERS.get(userId);
            members.add(new CommonApiClient.CommonMember(userId, u.name, u.phone, u.email,
                    businessByUser.get(userId) ? "BUSINESS" : "OUTLET", null, levels, Set.of()));
        });
        return members;
    }

    // ── directory reads, as common answers them ────────────────────────────

    static List<CommonApiClient.AppOutlet> appOutlets(CommonApiClient.OutletQuery q) {
        List<Place> rows = new ArrayList<>(PLACES.values());
        if (q.ids() != null) {
            Set<Long> ids = Set.copyOf(q.ids());
            rows.removeIf(p -> !ids.contains(p.id));
        }
        if (q.orgIds() != null) {
            Set<Long> ids = Set.copyOf(q.orgIds());
            rows.removeIf(p -> !ids.contains(p.orgId));
        }
        if (q.kinds() != null && !q.kinds().isEmpty()) {
            Set<String> kinds = q.kinds().stream().map(k -> k.toUpperCase(Locale.ROOT)).collect(
                    java.util.stream.Collectors.toSet());
            rows.removeIf(p -> !kinds.contains(p.kind));
        }
        if (q.statuses() != null && !q.statuses().isEmpty()) {
            rows.removeIf(p -> !q.statuses().contains(p.status));
        }
        if (q.orgType() != null) {
            rows.removeIf(p -> !q.orgType().equalsIgnoreCase(ORGS.get(p.orgId).orgType));
        }
        Map<Long, Double> distance = new LinkedHashMap<>();
        if (q.near() != null) {
            rows.removeIf(p -> p.latitude == null || p.longitude == null);
            rows.forEach(p -> distance.put(p.id, km(q.near().latitude(), q.near().longitude(),
                    p.latitude.doubleValue(), p.longitude.doubleValue())));
            rows.removeIf(p -> distance.get(p.id) > q.near().radiusKm());
            rows.sort(Comparator.comparing((Place p) -> distance.get(p.id)).thenComparing(p -> p.id));
        } else {
            rows.sort(Comparator.comparing(p -> p.id));
        }
        int limit = q.limit() == null || q.limit() <= 0 ? 5000 : Math.min(q.limit(), 5000);
        return rows.stream().limit(limit).map(p -> appOutlet(p, distance.get(p.id))).toList();
    }

    static CommonApiClient.AppOutlet appOutlet(Place p, Double distanceKm) {
        Org o = ORGS.get(p.orgId);
        Optional<GstState> state = gstState(p.stateCode);
        return new CommonApiClient.AppOutlet(p.id, p.orgId, p.kind, p.name, p.addressLine1, p.addressLine2,
                p.landmark, null, p.city, p.stateCode, "IN", p.pincode, p.latitude, p.longitude, p.googlePlaceId,
                p.formattedAddress, p.contactName, p.contactPhone, null, "Asia/Kolkata", "INR", null, p.status,
                p.hours, o.orgType, o.displayName, o.legalName, o.contactName, o.contactPhone, o.contactEmail,
                o.status, o.claimStatus, o.verificationStatus, o.gstin, o.live, o.enrollmentStatus,
                effectiveSettings(p), distanceKm == null ? null : Math.round(distanceKm * 1000) / 1000.0,
                p.createdAt.toString(), state.map(GstState::stateName).orElse(null),
                state.map(GstState::code).orElse(null));
    }

    static Map<String, Object> effectiveSettings(Place p) {
        Map<String, Object> s = new LinkedHashMap<>(DEFAULTS);
        s.putAll(p.settings);
        return s;
    }

    static CommonApiClient.OrgPage appOrgs(CommonApiClient.OrgQuery q) {
        List<Org> rows = new ArrayList<>(ORGS.values());
        if (q.ids() != null) {
            Set<Long> ids = Set.copyOf(q.ids());
            rows.removeIf(o -> !ids.contains(o.id));
        }
        if (q.orgType() != null) {
            rows.removeIf(o -> !q.orgType().equalsIgnoreCase(o.orgType));
        }
        if (q.q() != null && !q.q().isBlank()) {
            String term = q.q().trim().toLowerCase(Locale.ROOT);
            rows.removeIf(o -> !(contains(o.displayName, term) || contains(o.legalName, term)
                    || contains(o.gstin, term)));
        }
        rows.sort(Comparator.comparing((Org o) -> o.id).reversed());
        int offset = q.offset() == null ? 0 : Math.max(0, q.offset());
        int limit = q.limit() == null || q.limit() <= 0 ? 5000 : Math.min(q.limit(), 5000);
        List<CommonApiClient.AppOrg> page = rows.stream().skip(offset).limit(limit).map(LocalCommon::appOrg).toList();
        return new CommonApiClient.OrgPage(page, rows.size());
    }

    static CommonApiClient.AppOrg appOrg(Org o) {
        return new CommonApiClient.AppOrg(o.id, o.orgType, o.displayName, o.legalName, o.contactName, o.contactPhone,
                o.contactEmail, null, null, null, null, null, o.claimStatus, o.verificationStatus, o.status,
                o.createdAt.toString(), o.id, o.enrollmentStatus, o.live, o.gstin);
    }

    private static CommonApiClient.CommonUser commonUser(User u) {
        return new CommonApiClient.CommonUser(u.id, u.name, u.phone, u.email, u.status, "en", null);
    }

    // ── helpers ────────────────────────────────────────────────────────────

    static Org org(long id) {
        Org o = ORGS.get(id);
        if (o == null) {
            throw new IllegalArgumentException("No business " + id);
        }
        return o;
    }

    static Place place(long id) {
        Place p = PLACES.get(id);
        if (p == null) {
            throw new IllegalArgumentException("No outlet or store " + id);
        }
        return p;
    }

    private static double km(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static boolean contains(String v, String term) {
        return v != null && v.toLowerCase(Locale.ROOT).contains(term);
    }

    /** ISO 3166-2 codes of the GST states, as common's geo_state keys them. */
    private static final Map<String, String> ISO_BY_GST = Map.ofEntries(Map.entry("01", "IN-JK"),
            Map.entry("02", "IN-HP"), Map.entry("03", "IN-PB"), Map.entry("04", "IN-CH"), Map.entry("05", "IN-UT"),
            Map.entry("06", "IN-HR"), Map.entry("07", "IN-DL"), Map.entry("08", "IN-RJ"), Map.entry("09", "IN-UP"),
            Map.entry("10", "IN-BR"), Map.entry("11", "IN-SK"), Map.entry("12", "IN-AR"), Map.entry("13", "IN-NL"),
            Map.entry("14", "IN-MN"), Map.entry("15", "IN-MZ"), Map.entry("16", "IN-TR"), Map.entry("17", "IN-ML"),
            Map.entry("18", "IN-AS"), Map.entry("19", "IN-WB"), Map.entry("20", "IN-JH"), Map.entry("21", "IN-OR"),
            Map.entry("22", "IN-CT"), Map.entry("23", "IN-MP"), Map.entry("24", "IN-GJ"), Map.entry("26", "IN-DH"),
            Map.entry("27", "IN-MH"), Map.entry("29", "IN-KA"), Map.entry("30", "IN-GA"), Map.entry("31", "IN-LD"),
            Map.entry("32", "IN-KL"), Map.entry("33", "IN-TN"), Map.entry("34", "IN-PY"), Map.entry("35", "IN-AN"),
            Map.entry("36", "IN-TG"), Map.entry("37", "IN-AP"), Map.entry("38", "IN-LA"));

    /** A state as typed (name, alias or ISO code) as common's ISO code; unknown text is kept as typed. */
    static String stateCode(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String s = state.trim();
        if (s.toUpperCase(Locale.ROOT).startsWith("IN-")) {
            return s.toUpperCase(Locale.ROOT);
        }
        return GstState.ofName(s).map(g -> ISO_BY_GST.get(g.code())).orElse(s);
    }

    private static Optional<GstState> gstState(String isoCode) {
        if (isoCode == null) {
            return Optional.empty();
        }
        return ISO_BY_GST.entrySet().stream().filter(e -> e.getValue().equals(isoCode)).findFirst()
                .flatMap(e -> GstState.ofCode(e.getKey()));
    }

    static String str(Map<String, Object> in, String key) {
        Object v = in == null ? null : in.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static BigDecimal decimal(Object v) {
        return v == null ? null : new BigDecimal(String.valueOf(v));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Map<?, ?> m ? (Map<String, Object>) m : JSON.convertValue(v, Map.class);
    }
}
