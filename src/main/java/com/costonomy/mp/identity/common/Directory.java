package com.costonomy.mp.identity.common;

import com.costonomy.security.client.CommonApiClient;
import com.costonomy.security.client.CommonApiClient.AppOrg;
import com.costonomy.security.client.CommonApiClient.AppOutlet;
import com.costonomy.security.client.CommonApiClient.CommonUser;
import com.costonomy.security.client.CommonApiClient.OrgPage;
import com.costonomy.security.client.CommonApiClient.OrgQuery;
import com.costonomy.security.client.CommonApiClient.OutletQuery;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Businesses, outlets, stores and people, read from costonomy-common-api (D-208). Marketplace keeps no copies:
 * restaurants and suppliers are common's businesses, outlets and stores common's outlets (kind OUTLET / STORE),
 * and Marketplace's own fields about them are common's MARKETPLACE outlet settings.
 *
 * <p>Lookups by id are kept for {@code costonomy.mp.directory.ttl} (30 s) so a request that names the same store
 * twenty times asks once; common's change notifications ({@link CommonEvents}) drop an entry as soon as it changes.
 * Queries are not cached. The tests set the TTL to zero: their stand-in for common changes things directly.
 */
@Service
public class Directory {

    private final CommonApiClient common;
    private final Cache<Long, AppOutlet> outlets;
    private final Cache<Long, AppOrg> orgs;
    private final Cache<Long, CommonUser> users;

    public Directory(CommonApiClient common,
                     @Value("${costonomy.mp.directory.ttl:PT30S}") Duration ttl) {
        this.common = common;
        this.outlets = cache(ttl);
        this.orgs = cache(ttl);
        this.users = cache(ttl);
    }

    private static <V> Cache<Long, V> cache(Duration ttl) {
        return Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(ttl.isZero() ? 0 : 20_000).build();
    }

    // ── outlets and stores ─────────────────────────────────────────────

    public Optional<AppOutlet> outlet(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(outlets(List.of(id)).get(id));
    }

    /** By id, in the order asked; ids common does not know are left out. */
    public Map<Long, AppOutlet> outlets(Collection<Long> ids) {
        return byId(ids, outlets, missing -> common.appOutlets(OutletQuery.ids(missing)).stream()
                .collect(LinkedHashMap::new, (m, o) -> m.put(o.id(), o), Map::putAll));
    }

    /** Every outlet or store of a business. */
    public List<AppOutlet> outletsOf(long orgId) {
        List<AppOutlet> all = common.appOutlets(OutletQuery.orgs(List.of(orgId)));
        all.forEach(o -> outlets.put(o.id(), o));
        return all;
    }

    /** Any outlet query (stores near a point, the app's stores, ...); fresh every time. */
    public List<AppOutlet> query(OutletQuery query) {
        List<AppOutlet> all = common.appOutlets(query);
        all.forEach(o -> outlets.put(o.id(), o));
        return all;
    }

    /** Every store in the Marketplace, nearest first when {@code near} is given. */
    public List<AppOutlet> stores(CommonApiClient.Near near) {
        return query(new OutletQuery(null, null, List.of("STORE"), "SUPPLIER", null, near, null));
    }

    // ── businesses ─────────────────────────────────────────────────────

    public Optional<AppOrg> org(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(orgs(List.of(id)).get(id));
    }

    public Map<Long, AppOrg> orgs(Collection<Long> ids) {
        return byId(ids, orgs, missing -> common.appOrgs(OrgQuery.ids(missing)).items().stream()
                .collect(LinkedHashMap::new, (m, o) -> m.put(o.id(), o), Map::putAll));
    }

    public OrgPage orgs(OrgQuery query) {
        OrgPage page = common.appOrgs(query);
        page.items().forEach(o -> orgs.put(o.id(), o));
        return page;
    }

    // ── people ─────────────────────────────────────────────────────────

    public Optional<CommonUser> user(Long id) {
        return id == null ? Optional.empty() : Optional.ofNullable(users(List.of(id)).get(id));
    }

    public Map<Long, CommonUser> users(Collection<Long> ids) {
        return byId(ids, users, missing -> common.users(missing).stream()
                .collect(LinkedHashMap::new, (m, u) -> m.put(u.id(), u), Map::putAll));
    }

    // ── change notifications ───────────────────────────────────────────

    public void evictOutlet(long id) {
        outlets.invalidate(id);
    }

    /** A business changed: it, and every outlet row that carries its name and status. */
    public void evictOrg(long id) {
        orgs.invalidate(id);
        outlets.asMap().values().removeIf(o -> o.orgId() == id);
    }

    public void evictUser(long id) {
        users.invalidate(id);
    }

    public void evictAll() {
        outlets.invalidateAll();
        orgs.invalidateAll();
        users.invalidateAll();
    }

    private static <V> Map<Long, V> byId(Collection<Long> ids, Cache<Long, V> cache,
                                         Function<List<Long>, Map<Long, V>> load) {
        List<Long> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        Map<Long, V> found = new LinkedHashMap<>(cache.getAllPresent(wanted));
        List<Long> missing = wanted.stream().filter(id -> !found.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            Map<Long, V> loaded = load.apply(missing);
            cache.putAll(loaded);
            found.putAll(loaded);
        }
        Map<Long, V> ordered = new LinkedHashMap<>();
        wanted.forEach(id -> {
            V v = found.get(id);
            if (v != null) {
                ordered.put(id, v);
            }
        });
        return ordered;
    }
}
