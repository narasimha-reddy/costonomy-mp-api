package com.costonomy.mp.identity.common;

import com.costonomy.mp.access.service.RolePermissionCatalog;
import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.identity.service.PhoneNumbers;
import com.costonomy.security.client.CommonHttp;
import com.costonomy.security.client.CommonApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link CommonDirectory} over common's public API, sent with the caller's own bearer token (it carries the COMMON
 * audience), so common records the change as theirs and applies its own permission checks.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HttpCommonDirectory implements CommonDirectory {

    private static final String APP = "MARKETPLACE";

    private final CommonHttp http;
    private final CommonApiClient common;
    private final RolePermissionCatalog catalog;
    private final ObjectMapper json;

    @Override
    public long createOrg(long actorId, Business b) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orgType", b.orgType());
        body.put("displayName", b.displayName());
        body.put("legalName", b.legalName());
        body.put("contactName", b.contactName());
        body.put("contactPhone", b.contactPhone());
        body.put("contactEmail", b.contactEmail());
        body.put("gstin", blankToNull(b.gstin()));
        body.put("enrollApps", List.of(APP));
        body.put("ignoreClaimCandidates", true);
        return send("POST", "/v1/orgs", body).path("id").asLong();
    }

    @Override
    public long createOutlet(long actorId, long orgId, String orgType, Place p) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", "SUPPLIER".equals(orgType) ? "STORE" : "OUTLET");
        body.put("name", p.name());
        body.put("addressLine1", p.addressLine1());
        body.put("addressLine2", p.addressLine2());
        body.put("landmark", p.landmark());
        body.put("city", p.city());
        body.put("stateCode", stateCode(p.state()));
        body.put("pincode", p.pincode());
        body.put("latitude", p.latitude());
        body.put("longitude", p.longitude());
        body.put("googlePlaceId", p.googlePlaceId());
        body.put("formattedAddress", p.formattedAddress());
        body.put("contactName", p.contactName());
        body.put("contactPhone", p.contactPhone());
        return send("POST", "/v1/orgs/" + orgId + "/outlets", body).path("id").asLong();
    }

    @Override
    public Invited invite(long actorId, long orgId, String phone, String country, String name, String roleCode,
                          Long outletId) {
        String target = PhoneNumbers.normalize(phone, country);
        String scopeType = outletId == null ? "ORG" : "OUTLET";
        long mpRole = roleId(APP, orgId, roleCode);
        // Common's own role decides what they can do in common itself (see the business, invite people); a
        // Marketplace admin who may manage the team gets common's admin role at the same scope.
        boolean managesTeam = catalog.permissionsForRole(roleCode).stream()
                .anyMatch(Set.of(Permissions.OUTLET_USER_MANAGE, Permissions.SUPPLIER_USER_MANAGE)::contains);
        String commonRole = managesTeam ? (outletId == null ? "ORG_ADMIN" : "OUTLET_MANAGER") : "MEMBER";
        List<Map<String, Object>> grants = new ArrayList<>();
        grants.add(grant(roleId("COMMON", orgId, commonRole), scopeType, outletId == null ? null : outletId));
        grants.add(grant(mpRole, scopeType, outletId == null ? null : outletId));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("target", target);
        body.put("targetType", "PHONE");
        body.put("name", blankToNull(name));
        body.put("grants", grants);
        body.put("primaryOutletId", outletId);
        body.put("appCode", APP);
        JsonNode invitation = send("POST", "/v1/orgs/" + orgId + "/invitations", body);

        // An existing account is added straight away; a new number joins when it first signs in.
        String digits = lastDigits(target);
        for (CommonApiClient.CommonMember m : common.members(orgId, outletId, null, null)) {
            if (m.phone() != null && lastDigits(m.phone()).equals(digits)) {
                return new Invited(m.userId(), target, m.name(), "ACTIVE");
            }
        }
        return new Invited(null, target, name, text(invitation, "status", "INVITED"));
    }

    private static Map<String, Object> grant(long roleId, String scopeType, Long scopeId) {
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("roleId", roleId);
        g.put("scopeType", scopeType);
        g.put("scopeId", scopeId);
        return g;
    }

    private long roleId(String app, long orgId, String code) {
        for (JsonNode r : send("GET", "/v1/roles?app=" + app + "&orgId=" + orgId, null).path("items")) {
            if (code.equals(r.path("code").asText())) {
                return r.path("id").asLong();
            }
        }
        throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Unknown role: " + code);
    }

    /** Common's ISO code (IN-TG) for a state given by code or name; null when it isn't recognised. */
    private String stateCode(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String s = state.trim();
        for (JsonNode st : send("GET", "/v1/geo/states", null).path("items")) {
            String code = st.path("code").asText();
            if (code.equalsIgnoreCase(s) || st.path("name").asText().equalsIgnoreCase(s)
                    || code.toUpperCase(Locale.ROOT).endsWith("-" + s.toUpperCase(Locale.ROOT))) {
                return code;
            }
        }
        return null;
    }

    private JsonNode send(String method, String path, Object body) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.AUTHORIZATION, bearer());
        headers.put(HttpHeaders.ACCEPT, "application/json");
        String payload = null;
        if (body != null) {
            headers.put(HttpHeaders.CONTENT_TYPE, "application/json");
            headers.put("Idempotency-Key", UUID.randomUUID().toString());
            try {
                payload = json.writeValueAsString(body);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
        CommonHttp.Response r = http.send(method, path, headers, payload);
        JsonNode node = parse(r.body());
        if (r.ok()) {
            return node;
        }
        String message = text(node.path("error"), "message", "costonomy-common-api refused the change");
        log.info("common-api {} {} -> {} {}", method, path, r.status(), message);
        throw switch (r.status()) {
            case 401, 403 -> new BusinessException(ErrorCode.FORBIDDEN, message);
            case 404 -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, message);
            default -> new BusinessException(ErrorCode.VALIDATION_ERROR, message,
                    node.path("error").has("details") ? json.convertValue(node.path("error").path("details"), Map.class)
                            : Map.of());
        };
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    /** The caller's token, as it reached Marketplace. */
    private static String bearer() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes a) {
            HttpServletRequest request = a.getRequest();
            String header = request.getHeader(HttpHeaders.AUTHORIZATION);
            if (header != null && header.startsWith("Bearer ")) {
                return header;
            }
        }
        throw new BusinessException(ErrorCode.UNAUTHENTICATED);
    }

    private static String text(JsonNode node, String field, String fallback) {
        String v = node.path(field).asText(null);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static String lastDigits(String phone) {
        String d = phone.replaceAll("\\D", "");
        return d.length() > 10 ? d.substring(d.length() - 10) : d;
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
