package com.costonomy.mp.access;

import com.costonomy.mp.access.domain.ScopeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScopeTypeTest {

    @Test
    @DisplayName("a restaurant-level grant reaches that restaurant's outlets")
    void outletInheritsFromRestaurant() {
        // An owner should not need re-granting for every outlet they open.
        assertThat(ScopeType.OUTLET.satisfyingScopes())
                .containsExactlyInAnyOrder(ScopeType.OUTLET, ScopeType.RESTAURANT, ScopeType.PLATFORM);
    }

    @Test
    @DisplayName("an outlet-level grant does not reach the restaurant")
    void inheritanceDoesNotRunUpwards() {
        // A store manager at one outlet must not be able to edit the restaurant
        // or reach its other outlets.
        assertThat(ScopeType.RESTAURANT.satisfyingScopes())
                .doesNotContain(ScopeType.OUTLET);
    }

    @Test
    @DisplayName("a store-level grant does not reach the supplier organisation")
    void supplierHierarchyMirrorsRestaurant() {
        assertThat(ScopeType.SUPPLIER_STORE.satisfyingScopes())
                .containsExactlyInAnyOrder(
                        ScopeType.SUPPLIER_STORE, ScopeType.SUPPLIER, ScopeType.PLATFORM);
        assertThat(ScopeType.SUPPLIER.satisfyingScopes()).doesNotContain(ScopeType.SUPPLIER_STORE);
    }

    @Test
    @DisplayName("the restaurant and supplier hierarchies never cross")
    void worldsAreDisjoint() {
        // A supplier grant must never satisfy a restaurant check, or one
        // marketplace participant could act as another.
        assertThat(ScopeType.OUTLET.satisfyingScopes())
                .doesNotContain(ScopeType.SUPPLIER, ScopeType.SUPPLIER_STORE);
        assertThat(ScopeType.SUPPLIER_STORE.satisfyingScopes())
                .doesNotContain(ScopeType.RESTAURANT, ScopeType.OUTLET);
    }

    @Test
    @DisplayName("platform satisfies only platform")
    void platformIsTerminal() {
        assertThat(ScopeType.PLATFORM.satisfyingScopes()).containsExactly(ScopeType.PLATFORM);
    }
}
