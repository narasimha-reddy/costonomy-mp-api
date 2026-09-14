package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.ScopeType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Resolves a scope to its parent.
 *
 * <p>An outlet belongs to a restaurant and a store to a supplier organisation, so
 * a grant at the parent satisfies a check at the child — an owner does not need
 * re-granting for every outlet they open.
 *
 * <p>Uses {@link JdbcTemplate} rather than the restaurant and supplier
 * repositories on purpose: those live in other modules, and the access module
 * reaching into another module's repositories is exactly the coupling the modular
 * monolith rules forbid. Two integer lookups against indexed primary keys are a
 * cheaper dependency than an inverted one.
 */
@Service
@RequiredArgsConstructor
public class ScopeResolver {

    private final JdbcTemplate jdbc;

    /**
     * The parent scope of {@code (type, id)}, if it has one.
     *
     * <p>Returns empty for a scope with no parent, and also for an id that does
     * not exist — the caller is performing an authorization check, and a
     * non-existent outlet grants nothing either way.
     */
    public Optional<ScopeRef> parentOf(ScopeType scopeType, Long scopeId) {
        if (scopeId == null) {
            return Optional.empty();
        }

        return switch (scopeType) {
            case OUTLET -> queryParent(
                    "select restaurant_id from outlet where id = ?", scopeId, ScopeType.RESTAURANT);
            case SUPPLIER_STORE -> queryParent(
                    "select supplier_organization_id from supplier_store where id = ?",
                    scopeId, ScopeType.SUPPLIER);
            case RESTAURANT, SUPPLIER, PLATFORM -> Optional.empty();
        };
    }

    private Optional<ScopeRef> queryParent(String sql, Long scopeId, ScopeType parentType) {
        var ids = jdbc.queryForList(sql, Long.class, scopeId);
        return ids.isEmpty() || ids.get(0) == null
                ? Optional.empty()
                : Optional.of(new ScopeRef(parentType, ids.get(0)));
    }

    /** A scope, as a value. */
    public record ScopeRef(ScopeType type, Long id) {
    }
}
