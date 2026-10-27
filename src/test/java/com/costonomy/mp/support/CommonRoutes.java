package com.costonomy.mp.support;

import com.costonomy.mp.supplier.domain.SupplierLifecycleStatus;
import com.costonomy.mp.supplier.domain.SupplierStanding;
import com.costonomy.security.access.AccessSnapshot;
import com.costonomy.security.client.CommonApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The calls the Marketplace app now makes to costonomy-common-api (D-208), answered by {@link LocalCommon}: creating
 * and editing restaurants, outlets, suppliers and stores, team invitations and supplier verification. The tests
 * still write them as the Marketplace calls they used to be ({@code POST /api/v1/suppliers} ...), so a journey
 * reads the same; {@link ApiClient} sends them here, and the answers keep the old shapes ({@code data.stores[0].id}).
 *
 * <p>Access is checked the way the old endpoints did it: a caller without the permission at that scope gets 404.
 */
final class CommonRoutes {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ID = Pattern.compile("/api/v1/(restaurants|outlets|suppliers|supplier-stores)"
            + "(?:/(\\d+))?(?:/(outlets|users|stores|verification))?");
    private static final Pattern REVIEW = Pattern.compile("/api/v1/admin/suppliers/verifications/(\\d+)/review");

    record Answer(int status, JsonNode body) {
    }

    private CommonRoutes() {
    }

    static Optional<Answer> handle(String method, String rawPath, String token, Object body) {
        String path = rawPath.split("\\?")[0];
        Matcher review = REVIEW.matcher(path);
        if (review.matches() && "POST".equals(method)) {
            return Optional.of(review(token, Long.parseLong(review.group(1)), LocalCommon.map(body)));
        }
        Matcher m = ID.matcher(path);
        if (!m.matches()) {
            return Optional.empty();
        }
        String kind = m.group(1);
        Long id = m.group(2) == null ? null : Long.parseLong(m.group(2));
        String sub = m.group(3);
        long actor = LocalCommon.userIdOf(token);
        Map<String, Object> in = body == null ? Map.of() : LocalCommon.map(body);
        try {
            return Optional.ofNullable(route(method, kind, id, sub, actor, in));
        } catch (IllegalArgumentException e) {
            return Optional.of(error(400, "VALIDATION_ERROR", e.getMessage()));
        }
    }

    private static Answer route(String method, String kind, Long id, String sub, long actor, Map<String, Object> in) {
        switch (kind) {
            case "restaurants" -> {
                if (id == null && sub == null && "POST".equals(method)) {
                    return ok(restaurant(LocalCommon.createRestaurant(actor, in)));
                }
                if (id == null || !isOrg(id, "RESTAURANT")) {
                    return id == null ? null : notFound("Restaurant");
                }
                if (sub == null) {
                    return switch (method) {
                        case "GET" -> allowed(actor, "business.view", id, null) ? ok(restaurant(org(id)))
                                : notFound("Restaurant");
                        case "PATCH" -> {
                            if (!allowed(actor, "business.edit", id, null)) {
                                yield notFound("Restaurant");
                            }
                            editOrg(org(id), in, "name");
                            yield ok(restaurant(org(id)));
                        }
                        default -> null;
                    };
                }
                if ("outlets".equals(sub)) {
                    if ("POST".equals(method)) {
                        return allowed(actor, "business.edit", id, null)
                                ? ok(outlet(LocalCommon.createPlace(id, "OUTLET", in))) : notFound("Restaurant");
                    }
                    return allowed(actor, "business.view", id, null) ? ok(places(id, "OUTLET")) : notFound("Restaurant");
                }
                return null;
            }
            case "outlets" -> {
                if (id == null || !isPlace(id, "OUTLET")) {
                    return null;
                }
                long orgId = LocalCommon.orgOf(id);
                if (sub == null) {
                    return switch (method) {
                        case "GET" -> allowed(actor, "outlets.view", orgId, id) ? ok(outlet(place(id)))
                                : notFound("Outlet");
                        case "PATCH" -> {
                            if (!allowed(actor, "outlets.edit", orgId, id)) {
                                yield notFound("Outlet");
                            }
                            LocalCommon.update(id, in);
                            yield ok(outlet(place(id)));
                        }
                        default -> null;
                    };
                }
                if ("users".equals(sub)) {
                    if (!allowed(actor, "team.edit", orgId, id)) {
                        return notFound("Outlet");
                    }
                    if ("POST".equals(method)) {
                        long userId = LocalCommon.invite(orgId, LocalCommon.str(in, "phone"),
                                LocalCommon.str(in, "country"), LocalCommon.str(in, "name"),
                                ((Number) in.get("roleId")).longValue(), id);
                        return ok(member(userId, orgId, id));
                    }
                    return ok(JSON.valueToTree(LocalCommon.members(orgId, id, null, null)));
                }
                return null;
            }
            case "suppliers" -> {
                if (id == null && sub == null && "POST".equals(method)) {
                    return ok(supplier(LocalCommon.createSupplier(actor, in)));
                }
                if (id == null || !isOrg(id, "SUPPLIER")) {
                    return id == null ? null : notFound("Supplier");
                }
                if (sub == null) {
                    return switch (method) {
                        case "GET" -> allowed(actor, "business.view", id, null) ? ok(supplier(org(id)))
                                : notFound("Supplier");
                        case "PATCH" -> {
                            if (!allowed(actor, "business.edit", id, null)) {
                                yield notFound("Supplier");
                            }
                            editOrg(org(id), in, "displayName");
                            yield ok(supplier(org(id)));
                        }
                        default -> null;
                    };
                }
                return switch (sub) {
                    case "stores" -> "POST".equals(method) && allowed(actor, "business.edit", id, null)
                            ? ok(store(LocalCommon.createPlace(id, "STORE", in))) : notFound("Supplier");
                    case "verification" -> {
                        if (!allowed(actor, "business.edit", id, null)) {
                            yield notFound("Supplier");
                        }
                        if ("POST".equals(method)) {
                            long vid = LocalCommon.submitVerification(id, LocalCommon.str(in, "gstin"));
                            yield ok(verification(vid, id));
                        }
                        yield ok(JSON.valueToTree(LocalCommon.VERIFICATIONS.entrySet().stream()
                                .filter(e -> e.getValue() == id).map(e -> verificationMap(e.getKey(), id)).toList()));
                    }
                    case "users" -> {
                        if (!allowed(actor, "team.edit", id, null)) {
                            yield notFound("Supplier");
                        }
                        Long storeId = in.get("storeId") == null ? null : ((Number) in.get("storeId")).longValue();
                        if (storeId != null && (!isPlace(storeId, "STORE") || LocalCommon.orgOf(storeId) != id)) {
                            yield notFound("SupplierStore");
                        }
                        long userId = LocalCommon.invite(id, LocalCommon.str(in, "phone"),
                                LocalCommon.str(in, "country"), LocalCommon.str(in, "name"),
                                ((Number) in.get("roleId")).longValue(), storeId);
                        yield ok(member(userId, id, storeId));
                    }
                    default -> null;
                };
            }
            case "supplier-stores" -> {
                if (id == null || sub != null || !isPlace(id, "STORE")) {
                    return null;
                }
                long orgId = LocalCommon.orgOf(id);
                return switch (method) {
                    case "GET" -> allowed(actor, "outlets.view", orgId, id) ? ok(store(place(id)))
                            : notFound("SupplierStore");
                    case "PATCH" -> {
                        if (!allowed(actor, "outlets.edit", orgId, id)) {
                            yield notFound("SupplierStore");
                        }
                        LocalCommon.update(id, in);
                        yield ok(store(place(id)));
                    }
                    default -> null;
                };
            }
            default -> {
                return null;
            }
        }
    }

    /** Common's verification queue: Costonomy staff with SUPPLIER_VERIFY decide. */
    private static Answer review(String token, long verificationId, Map<String, Object> in) {
        long actor = LocalCommon.userIdOf(token);
        AccessSnapshot s = LocalCommon.snapshot(actor);
        if (s == null || !s.platformPermissions().contains(com.costonomy.mp.access.domain.Permissions.SUPPLIER_VERIFY)) {
            return error(403, "PERMISSION_DENIED", "Not allowed");
        }
        Long supplierId = LocalCommon.VERIFICATIONS.get(verificationId);
        if (supplierId == null) {
            return notFound("SupplierVerification");
        }
        if (Boolean.TRUE.equals(in.get("approved"))) {
            LocalCommon.verify(supplierId);
        } else {
            LocalCommon.reject(supplierId);
        }
        return ok(verification(verificationId, supplierId));
    }

    // ── access ─────────────────────────────────────────────────────────────

    private static boolean allowed(long userId, String permission, long orgId, Long outletId) {
        AccessSnapshot s = LocalCommon.snapshot(userId);
        if (s == null || !s.usable()) {
            return false;
        }
        for (AccessSnapshot.Org o : s.orgs()) {
            if (o.orgId() != orgId) {
                continue;
            }
            if (o.permissions() != null && o.permissions().contains(permission)) {
                return true;
            }
            for (AccessSnapshot.Outlet x : o.outlets()) {
                boolean here = outletId == null || x.outletId() == outletId;
                if (here && x.permissions().contains(permission)) {
                    return true;
                }
            }
        }
        return false;
    }

    // ── shapes ─────────────────────────────────────────────────────────────

    private static boolean isOrg(long id, String type) {
        LocalCommon.Org o = LocalCommon.ORGS.get(id);
        return o != null && type.equals(o.orgType);
    }

    private static boolean isPlace(long id, String kind) {
        LocalCommon.Place p = LocalCommon.PLACES.get(id);
        return p != null && kind.equals(p.kind);
    }

    private static LocalCommon.Org org(long id) {
        return LocalCommon.org(id);
    }

    private static LocalCommon.Place place(long id) {
        return LocalCommon.place(id);
    }

    private static void editOrg(LocalCommon.Org o, Map<String, Object> in, String nameKey) {
        if (in.get(nameKey) != null) o.displayName = LocalCommon.str(in, nameKey);
        if (in.get("legalName") != null) o.legalName = LocalCommon.str(in, "legalName");
        if (in.get("gstin") != null) o.gstin = LocalCommon.str(in, "gstin");
        if (in.get("contactName") != null) o.contactName = LocalCommon.str(in, "contactName");
        if (in.get("contactPhone") != null) o.contactPhone = LocalCommon.str(in, "contactPhone");
        if (in.get("contactEmail") != null) o.contactEmail = LocalCommon.str(in, "contactEmail");
    }

    private static Map<String, Object> restaurant(LocalCommon.Org o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("name", o.displayName);
        m.put("legalName", o.legalName);
        m.put("gstin", o.gstin);
        m.put("status", o.status);
        m.put("outlets", placeMaps(o.id, "OUTLET"));
        return m;
    }

    private static Map<String, Object> supplier(LocalCommon.Org o) {
        CommonApiClient.AppOrg org = LocalCommon.appOrg(o);
        SupplierLifecycleStatus lifecycle = SupplierStanding.of(org);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id);
        m.put("legalName", o.legalName);
        m.put("displayName", o.displayName);
        m.put("gstin", o.gstin);
        m.put("contactName", o.contactName);
        m.put("contactPhone", o.contactPhone);
        m.put("contactEmail", o.contactEmail);
        m.put("lifecycleStatus", lifecycle.name());
        m.put("verificationStatus", SupplierStanding.verification(o.verificationStatus).name());
        m.put("canTrade", lifecycle == SupplierLifecycleStatus.ACTIVE);
        m.put("stores", placeMaps(o.id, "STORE"));
        return m;
    }

    private static List<Map<String, Object>> placeMaps(long orgId, String kind) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : LocalCommon.outletsOf(orgId)) {
            LocalCommon.Place p = place(id);
            if (kind.equals(p.kind)) {
                out.add("STORE".equals(kind) ? storeMap(p) : outletMap(p));
            }
        }
        return out;
    }

    private static JsonNode places(long orgId, String kind) {
        return JSON.valueToTree(placeMaps(orgId, kind));
    }

    private static Map<String, Object> outlet(LocalCommon.Place p) {
        return outletMap(p);
    }

    private static Map<String, Object> outletMap(LocalCommon.Place p) {
        CommonApiClient.AppOutlet o = LocalCommon.appOutlet(p, null);
        Map<String, Object> m = address(o);
        m.put("restaurantId", o.orgId());
        m.put("deliveryInstructions", o.setting(SupplierStanding.DELIVERY_INSTRUCTIONS, null));
        m.put("status", o.status());
        return m;
    }

    private static Map<String, Object> store(LocalCommon.Place p) {
        return storeMap(p);
    }

    private static Map<String, Object> storeMap(LocalCommon.Place p) {
        CommonApiClient.AppOutlet o = LocalCommon.appOutlet(p, null);
        Map<String, Object> m = address(o);
        m.put("supplierOrganizationId", o.orgId());
        var hours = SupplierStanding.hours(o);
        m.put("operatingHours", Map.of("days", hours.dayNames(), "opensAt", hours.opensAt().toString(),
                "closesAt", hours.closesAt().toString()));
        m.put("responseSlaSeconds", SupplierStanding.responseSlaSeconds(o));
        m.put("preparationMinutes", SupplierStanding.preparationMinutes(o));
        m.put("directOrdersEnabled", SupplierStanding.directOrdersEnabled(o));
        m.put("status", SupplierStanding.storeStatus(o));
        return m;
    }

    private static Map<String, Object> address(CommonApiClient.AppOutlet o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", o.id());
        m.put("name", o.name());
        m.put("addressLine1", o.addressLine1());
        m.put("addressLine2", o.addressLine2());
        m.put("landmark", o.landmark());
        m.put("city", o.city());
        m.put("state", o.stateName() != null ? o.stateName() : o.stateCode());
        m.put("pincode", o.pincode());
        m.put("latitude", o.latitude());
        m.put("longitude", o.longitude());
        m.put("contactName", o.contactName());
        m.put("contactPhone", o.contactPhone());
        return m;
    }

    private static Map<String, Object> member(long userId, long orgId, Long outletId) {
        LocalCommon.User u = LocalCommon.USERS.get(userId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", userId);
        m.put("phone", u.phone);
        m.put("name", u.name);
        m.put("membershipStatus", "ACTIVE");
        m.put("status", "ACTIVE");
        AccessSnapshot s = LocalCommon.snapshot(userId);
        List<String> permissions = new ArrayList<>();
        if (s != null) {
            s.orgs().stream().filter(o -> o.orgId() == orgId).forEach(o -> {
                if (outletId == null && o.permissions() != null) {
                    permissions.addAll(o.permissions());
                }
                o.outlets().stream().filter(x -> outletId == null || x.outletId() == outletId).findFirst()
                        .ifPresent(x -> permissions.addAll(x.permissions()));
            });
        }
        m.put("permissions", permissions.stream().distinct().sorted().toList());
        return m;
    }

    private static Map<String, Object> verification(long id, long supplierId) {
        return verificationMap(id, supplierId);
    }

    private static Map<String, Object> verificationMap(long id, long supplierId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("supplierOrganizationId", supplierId);
        m.put("verificationType", "GST");
        m.put("status", SupplierStanding.verification(org(supplierId).verificationStatus).name());
        return m;
    }

    private static Answer ok(Object data) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", data);
        envelope.put("error", null);
        return new Answer(200, JSON.valueToTree(envelope));
    }

    private static Answer notFound(String entity) {
        return error(404, "NOT_FOUND", entity + " not found");
    }

    private static Answer error(int status, String code, String message) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("data", null);
        envelope.put("error", Map.of("code", code, "message", message == null ? code : message));
        return new Answer(status, JSON.valueToTree(envelope));
    }
}
