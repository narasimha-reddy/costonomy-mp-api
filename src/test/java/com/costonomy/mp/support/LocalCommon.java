package com.costonomy.mp.support;

import com.costonomy.mp.identity.common.CommonDirectory;
import com.costonomy.mp.identity.common.MarketplaceCatalogPublisher;
import com.costonomy.mp.identity.service.PhoneNumbers;
import com.costonomy.security.CostonomySecurityProperties;
import com.costonomy.security.access.AccessClient;
import com.costonomy.security.access.AccessSnapshot;
import com.costonomy.security.client.CommonApiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * costonomy-common-api for the integration tests, in-process and backed by Marketplace's own tables.
 *
 * <p>Before the move to common, Marketplace held sign-in, businesses and grants itself, and the tests set up access
 * by writing {@code user_role} rows. The fake keeps that working: it answers common's access lookups from
 * {@code user_role} + {@code role_permission} on every call (so a grant written or revoked mid-test applies to the
 * next request, as common's change notifications make it do in production), and its writes (a new business, outlet
 * or member) land in the same tables.
 *
 * <p>Restaurants and suppliers are one id space in common; here they are two tables, so the supplier tables start
 * at {@link #SUPPLIER_IDS} to keep the ids apart.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalCommon {

    static final long SUPPLIER_IDS = 1_000_000L;
    private static final AtomicLong VERSION = new AtomicLong();
    /** The last context's store; sign-in runs outside any request, so any context's connection will do. */
    private static volatile Store last;

    @Bean
    @Primary
    AccessClient localAccessClient(JdbcTemplate jdbcTemplate) {
        Store store = init(jdbcTemplate);
        return new AccessClient(null, new ObjectMapper(), properties(), Clock.systemUTC()) {
            @Override
            public Optional<AccessSnapshot> get(long userId, String sid) {
                return Optional.ofNullable(store.snapshot(userId));
            }
        };
    }

    @Bean
    @Primary
    CommonApiClient localCommonApiClient(JdbcTemplate jdbcTemplate) {
        Store store = init(jdbcTemplate);
        return new CommonApiClient(null, new ObjectMapper(), properties(), Clock.systemUTC()) {
            @Override
            public Optional<CommonUser> user(long id) {
                return store.user(id);
            }

            @Override
            public List<CommonUser> users(Collection<Long> ids) {
                return ids.stream().map(store::user).flatMap(Optional::stream).toList();
            }

            @Override
            public Optional<CommonOrg> org(long id) {
                return store.org(id);
            }

            @Override
            public List<CommonOrg> orgs(Collection<Long> ids) {
                return ids.stream().map(store::org).flatMap(Optional::stream).toList();
            }

            @Override
            public Optional<CommonOutletDetail> outlet(long id) {
                return store.outlet(id);
            }

            @Override
            public List<CommonOutletDetail> outlets(Collection<Long> ids) {
                return ids.stream().map(store::outlet).flatMap(Optional::stream).toList();
            }

            @Override
            public List<CommonMember> members(long orgId, Long outletId, String permission, Integer roleLevel) {
                return store.members(orgId, outletId, permission, roleLevel);
            }

            @Override
            public List<CommonDevice> devices(long userId) {
                return List.of();
            }
        };
    }

    @Bean
    @Primary
    CommonDirectory localCommonDirectory(JdbcTemplate jdbcTemplate) {
        return init(jdbcTemplate).new Directory();
    }

    private static CostonomySecurityProperties properties() {
        CostonomySecurityProperties p = new CostonomySecurityProperties();
        p.setApp("MARKETPLACE");
        return p;
    }

    private static synchronized Store init(JdbcTemplate jdbcTemplate) {
        if (last == null) {
            jdbcTemplate.execute("ALTER TABLE supplier_organization AUTO_INCREMENT = " + SUPPLIER_IDS);
            jdbcTemplate.execute("ALTER TABLE supplier_store AUTO_INCREMENT = " + SUPPLIER_IDS);
        }
        last = new Store(jdbcTemplate);
        return last;
    }

    /** Phone + code sign-in, as mp-mobile does it against common: the account is created on first use. */
    public static String signIn(String rawPhone) {
        return TestKeys.accessToken(last.userIdFor(PhoneNumbers.normalize(rawPhone), null));
    }

    /**
     * The fake over one test context's database connection. The run caches several contexts, each with its own pool;
     * a fake that wrote through another context's pool would block on its own request's open transaction.
     */
    static final class Store {

        private final JdbcTemplate jdbc;

        Store(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        // ── sign-in ──────────────────────────────────────────────────────────

        long userIdFor(String phone, String name) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM users WHERE phone = ?", Long.class, phone);
            if (!ids.isEmpty()) {
                return ids.get(0);
            }
            return insert("INSERT INTO users (phone, name, status, phone_verified_at) VALUES (?, ?, 'ACTIVE', NOW(6))",
                    phone, name);
        }

        // ── access ───────────────────────────────────────────────────────────

        private record Grant(String role, String roleName, String scopeType, Long scopeId) {
        }

        private static final class OrgAccess {
            final long orgId;
            final String orgType;
            final List<String> businessRoles = new ArrayList<>();
            final Map<Long, List<String>> outletRoles = new LinkedHashMap<>();
            final List<String> roleNames = new ArrayList<>();

            OrgAccess(long orgId, String orgType) {
                this.orgId = orgId;
                this.orgType = orgType;
            }
        }

        AccessSnapshot snapshot(long userId) {
            List<String> status = jdbc.queryForList("SELECT status FROM users WHERE id = ?", String.class, userId);
            if (status.isEmpty()) {
                return null;
            }
            List<Grant> grants = jdbc.query("SELECT r.code, r.name, ur.scope_type, ur.scope_id FROM user_role ur "
                            + "JOIN role r ON r.id = ur.role_id WHERE ur.user_id = ? AND ur.status = 'ACTIVE'",
                    (rs, i) -> new Grant(rs.getString(1), rs.getString(2), rs.getString(3),
                            rs.getObject(4) == null ? null : rs.getLong(4)), userId);

            List<String> platformRoles = new ArrayList<>();
            Set<String> platformPermissions = new TreeSet<>();
            Map<Long, OrgAccess> orgs = new LinkedHashMap<>();
            for (Grant g : grants) {
                switch (g.scopeType()) {
                    case "RESTAURANT", "SUPPLIER" -> {
                        OrgAccess o = orgs.computeIfAbsent(g.scopeId(), id -> new OrgAccess(id, g.scopeType()));
                        o.businessRoles.add(g.role());
                        o.roleNames.add(MarketplaceCatalogPublisher.publishedName(g.role(), g.roleName()));
                    }
                    case "OUTLET", "SUPPLIER_STORE" -> {
                        boolean store = "SUPPLIER_STORE".equals(g.scopeType());
                        Long orgId = orgOfOutlet(g.scopeId(), store);
                        if (orgId != null) {
                            orgs.computeIfAbsent(orgId, id -> new OrgAccess(id, store ? "SUPPLIER" : "RESTAURANT"))
                                    .outletRoles.computeIfAbsent(g.scopeId(), id -> new ArrayList<>()).add(g.role());
                        }
                    }
                    default -> {
                        platformRoles.add(g.role());
                        platformPermissions.addAll(permissions(List.of(g.role())));
                    }
                }
            }

            List<AccessSnapshot.Org> out = new ArrayList<>();
            for (OrgAccess o : orgs.values()) {
                boolean business = !o.businessRoles.isEmpty();
                Set<String> orgPermissions = permissions(o.businessRoles);
                List<Long> outletIds = new ArrayList<>(o.outletRoles.keySet());
                if (business) {
                    outletIds = outletsOf(o.orgId, "SUPPLIER".equals(o.orgType));
                }
                List<AccessSnapshot.Outlet> outlets = new ArrayList<>();
                for (Long outletId : outletIds) {
                    List<String> roles = new ArrayList<>(o.businessRoles);
                    roles.addAll(o.outletRoles.getOrDefault(outletId, List.of()));
                    outlets.add(new AccessSnapshot.Outlet(outletId, "ACTIVE", permissions(roles), Set.of(), levels(roles)));
                }
                out.add(new AccessSnapshot.Org(o.orgId, o.orgType, "ACTIVE", business ? "BUSINESS" : "OUTLET", null,
                        o.roleNames, orgPermissions, Set.of(), outlets));
            }
            return new AccessSnapshot(userId, status.get(0), true, "MARKETPLACE",
                    new AccessSnapshot.Platform(platformRoles, platformPermissions), out, null, VERSION.incrementAndGet());
        }

        private Set<String> permissions(List<String> roles) {
            if (roles.isEmpty()) {
                return Set.of();
            }
            return new TreeSet<>(jdbc.queryForList("SELECT DISTINCT p.code FROM role_permission rp "
                    + "JOIN role r ON r.id = rp.role_id JOIN permission p ON p.id = rp.permission_id WHERE r.code IN ("
                    + String.join(",", roles.stream().map(r -> "?").toList()) + ")", String.class, roles.toArray()));
        }

        private Set<Integer> levels(List<String> roles) {
            Set<Integer> levels = new TreeSet<>();
            roles.forEach(r -> levels.add(MarketplaceCatalogPublisher.level(r)));
            return levels;
        }

        private Long orgOfOutlet(Long outletId, boolean store) {
            List<Long> ids = jdbc.queryForList(store
                    ? "SELECT supplier_organization_id FROM supplier_store WHERE id = ?"
                    : "SELECT restaurant_id FROM outlet WHERE id = ?", Long.class, outletId);
            return ids.isEmpty() ? null : ids.get(0);
        }

        private List<Long> outletsOf(long orgId, boolean supplier) {
            return jdbc.queryForList(supplier
                    ? "SELECT id FROM supplier_store WHERE supplier_organization_id = ? ORDER BY id"
                    : "SELECT id FROM outlet WHERE restaurant_id = ? ORDER BY id", Long.class, orgId);
        }

        private boolean isSupplier(long orgId) {
            return count("SELECT COUNT(*) FROM supplier_organization WHERE id = ?", orgId) > 0;
        }

        // ── directory reads ──────────────────────────────────────────────────

        Optional<CommonApiClient.CommonUser> user(long id) {
            return jdbc.query("SELECT id, name, phone, email, status FROM users WHERE id = ?",
                    (rs, i) -> new CommonApiClient.CommonUser(rs.getLong(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), "en", null), id).stream().findFirst();
        }

        Optional<CommonApiClient.CommonOrg> org(long id) {
            if (isSupplier(id)) {
                return jdbc.query("SELECT id, display_name, legal_name FROM supplier_organization WHERE id = ?",
                        (rs, i) -> new CommonApiClient.CommonOrg(rs.getLong(1), "SUPPLIER", rs.getString(2),
                                rs.getString(3), "ACTIVE", null, null, null, null, List.of(), List.of()), id)
                        .stream().findFirst();
            }
            return jdbc.query("SELECT id, name, legal_name, status FROM restaurant WHERE id = ?",
                    (rs, i) -> new CommonApiClient.CommonOrg(rs.getLong(1), "RESTAURANT", rs.getString(2),
                            rs.getString(3), rs.getString(4), null, null, null, null, List.of(), List.of()), id)
                    .stream().findFirst();
        }

        Optional<CommonApiClient.CommonOutletDetail> outlet(long id) {
            boolean store = count("SELECT COUNT(*) FROM supplier_store WHERE id = ?", id) > 0;
            String sql = store
                    ? "SELECT id, supplier_organization_id, name, address_line1, city, state, pincode, latitude, longitude, "
                      + "contact_phone, status FROM supplier_store WHERE id = ?"
                    : "SELECT id, restaurant_id, name, address_line1, city, state, pincode, latitude, longitude, "
                      + "contact_phone, status FROM outlet WHERE id = ?";
            return jdbc.query(sql, (rs, i) -> {
                long orgId = rs.getLong(2);
                var outlet = new CommonApiClient.CommonOutlet(rs.getLong(1), orgId, store ? "STORE" : "OUTLET",
                        rs.getString(3), null, rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
                        rs.getBigDecimal(8), rs.getBigDecimal(9), rs.getString(10), null, null, rs.getString(11));
                var org = org(orgId).map(o -> new CommonApiClient.OrgSummary(o.id(), o.orgType(), o.displayName(),
                        o.status(), null)).orElse(null);
                return new CommonApiClient.CommonOutletDetail(outlet, org, List.of());
            }, id).stream().findFirst();
        }

        List<CommonApiClient.CommonMember> members(long orgId, Long outletId, String permission, Integer roleLevel) {
            boolean supplier = isSupplier(orgId);
            List<Long> outlets = outletsOf(orgId, supplier);
            Map<Long, List<String>> rolesByUser = new LinkedHashMap<>();
            Map<Long, Boolean> businessByUser = new LinkedHashMap<>();
            jdbc.query("SELECT ur.user_id, r.code, ur.scope_type, ur.scope_id FROM user_role ur JOIN role r ON r.id = ur.role_id "
                    + "WHERE ur.status = 'ACTIVE' AND ur.scope_type IN (?, ?) ORDER BY ur.user_id", rs -> {
                long userId = rs.getLong(1);
                String scopeType = rs.getString(3);
                long scopeId = rs.getLong(4);
                boolean business = scopeType.equals(supplier ? "SUPPLIER" : "RESTAURANT");
                boolean applies = business ? scopeId == orgId
                        : outletId == null ? outlets.contains(scopeId) : scopeId == outletId;
                if (applies) {
                    rolesByUser.computeIfAbsent(userId, id -> new ArrayList<>()).add(rs.getString(2));
                    businessByUser.merge(userId, business, Boolean::logicalOr);
                }
            }, supplier ? "SUPPLIER" : "RESTAURANT", supplier ? "SUPPLIER_STORE" : "OUTLET");

            List<CommonApiClient.CommonMember> members = new ArrayList<>();
            rolesByUser.forEach((userId, roles) -> {
                if (permission != null && !permissions(roles).contains(permission)) {
                    return;
                }
                Set<Integer> levels = levels(roles);
                if (roleLevel != null && levels.stream().noneMatch(l -> l <= roleLevel)) {
                    return;
                }
                user(userId).ifPresent(u -> members.add(new CommonApiClient.CommonMember(userId, u.name(),
                        u.primaryPhone(), u.primaryEmail(), businessByUser.get(userId) ? "BUSINESS" : "OUTLET", null,
                        levels, Set.of())));
            });
            return members;
        }

        // ── directory writes ─────────────────────────────────────────────────

        final class Directory implements CommonDirectory {

            @Override
            public long createOrg(long actorId, Business b) {
                boolean supplier = "SUPPLIER".equals(b.orgType());
                long id = supplier
                        ? insert("INSERT INTO supplier_organization (legal_name, display_name) VALUES (?, ?)",
                                b.legalName() == null ? b.displayName() : b.legalName(), b.displayName())
                        : insert("INSERT INTO restaurant (name, legal_name, status) VALUES (?, ?, 'ACTIVE')",
                                b.displayName(), b.legalName());
                grant(actorId, supplier ? "SUP_OWNER" : "REST_OWNER", supplier ? "SUPPLIER" : "RESTAURANT", id);
                return id;
            }

            @Override
            public long createOutlet(long actorId, long orgId, String orgType, Place p) {
                boolean supplier = "SUPPLIER".equals(orgType);
                return insert(supplier
                                ? "INSERT INTO supplier_store (supplier_organization_id, name, address_line1, city, state, "
                                  + "pincode, latitude, longitude, contact_phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
                                : "INSERT INTO outlet (restaurant_id, name, address_line1, city, state, pincode, latitude, "
                                  + "longitude, contact_phone) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        orgId, p.name(), text(p.addressLine1()), text(p.city()), text(p.state()), p.pincode(),
                        p.latitude(), p.longitude(), p.contactPhone());
            }

            @Override
            public Invited invite(long actorId, long orgId, String phone, String country, String name, String roleCode,
                                  Long outletId) {
                String normalized = PhoneNumbers.normalize(phone, country);
                long userId = userIdFor(normalized, name);
                boolean supplier = isSupplier(orgId);
                if (outletId == null) {
                    grant(userId, roleCode, supplier ? "SUPPLIER" : "RESTAURANT", orgId);
                } else {
                    grant(userId, roleCode, supplier ? "SUPPLIER_STORE" : "OUTLET", outletId);
                }
                String current = jdbc.queryForObject("SELECT name FROM users WHERE id = ?", String.class, userId);
                return new Invited(userId, normalized, current, "ACTIVE");
            }
        }

        void grant(long userId, String roleCode, String scopeType, long scopeId) {
            int reactivated = jdbc.update("UPDATE user_role ur JOIN role r ON r.id = ur.role_id SET ur.status = 'ACTIVE', "
                            + "ur.revoked_at = NULL WHERE ur.user_id = ? AND r.code = ? AND ur.scope_type = ? AND ur.scope_id = ?",
                    userId, roleCode, scopeType, scopeId);
            if (reactivated == 0) {
                int inserted = jdbc.update("INSERT INTO user_role (user_id, role_id, scope_type, scope_id, status) "
                        + "SELECT ?, id, ?, ?, 'ACTIVE' FROM role WHERE code = ?", userId, scopeType, scopeId, roleCode);
                if (inserted == 0) {
                    throw new com.costonomy.mp.common.error.BusinessException(
                            com.costonomy.mp.common.error.ErrorCode.VALIDATION_ERROR, "Unknown role: " + roleCode);
                }
            }
        }

        private long insert(String sql, Object... args) {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i] instanceof BigDecimal d ? d : args[i]);
                }
                return ps;
            }, keys);
            return keys.getKey().longValue();
        }

        private long count(String sql, Object... args) {
            Long n = jdbc.queryForObject(sql, Long.class, args);
            return n == null ? 0 : n;
        }

        private String text(String v) {
            return v == null ? "" : v;
        }
    }
}
