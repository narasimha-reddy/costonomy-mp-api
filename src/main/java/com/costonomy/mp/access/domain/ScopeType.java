package com.costonomy.mp.access.domain;

import java.util.List;

/**
 * What a role grant applies to.
 *
 * <p>Doc 03 §16: a permission on its own grants nothing. {@code ORDER_ACCEPT} is
 * always held <em>for a specific supplier store</em>, and holding it says nothing
 * about anyone else's store. Scope is what turns the permission catalogue into
 * tenant isolation.
 */
public enum ScopeType {

    /** Every outlet of one restaurant. */
    RESTAURANT,
    /** One outlet. */
    OUTLET,
    /** Every store of one supplier organisation. */
    SUPPLIER,
    /** One supplier store. */
    SUPPLIER_STORE,
    /**
     * The marketplace itself — internal operations roles. {@code scopeId} is null.
     *
     * <p>A platform grant satisfies a check at any scope, which sounds alarming
     * until you notice that no internal role holds a tenant permission: the V5
     * seed gives internal roles only {@code INTERNAL} and {@code SHARED}
     * permissions. So an operator can read any order ({@code ORDER_VIEW}, shared)
     * but cannot edit anyone's outlet ({@code OUTLET_EDIT}, restaurant-only).
     * The catalogue draws the boundary; the scope expansion does not have to.
     */
    PLATFORM;

    /**
     * The scopes whose grants satisfy a check at this scope.
     *
     * <p>A grant at {@code RESTAURANT} reaches every outlet of that restaurant, so
     * an owner need not be re-granted per outlet. Same for supplier → store.
     */
    public List<ScopeType> satisfyingScopes() {
        return switch (this) {
            case OUTLET -> List.of(OUTLET, RESTAURANT, PLATFORM);
            case RESTAURANT -> List.of(RESTAURANT, PLATFORM);
            case SUPPLIER_STORE -> List.of(SUPPLIER_STORE, SUPPLIER, PLATFORM);
            case SUPPLIER -> List.of(SUPPLIER, PLATFORM);
            case PLATFORM -> List.of(PLATFORM);
        };
    }

    public boolean isRestaurantSide() {
        return this == RESTAURANT || this == OUTLET;
    }

    public boolean isSupplierSide() {
        return this == SUPPLIER || this == SUPPLIER_STORE;
    }
}
