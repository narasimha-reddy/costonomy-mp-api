package com.costonomy.mp.access.service;

import com.costonomy.mp.access.domain.Role;
import com.costonomy.mp.access.repository.RoleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RoleCodeCacheTest {

    /** A clock the test moves by hand. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-10-06T10:00:00Z");

        void advance(java.time.Duration by) {
            now = now.plus(by);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private static Role role(long id, String code) {
        var role = mock(Role.class);
        when(role.getId()).thenReturn(id);
        when(role.getCode()).thenReturn(code);
        return role;
    }

    @Test
    @DisplayName("roles are read once, not on every permission check")
    void readOnce() {
        var repo = mock(RoleRepository.class);
        var owner = role(1, "OWNER");
        var staff = role(2, "STAFF");
        when(repo.findAll()).thenReturn(List.of(owner, staff));
        var cache = new RoleCodeCache(repo, new MovableClock());

        for (int i = 0; i < 50; i++) {
            assertThat(cache.codesById()).containsEntry(1L, "OWNER").containsEntry(2L, "STAFF");
        }

        verify(repo, times(1)).findAll();
    }

    @Test
    @DisplayName("they are read again after the time to live, so a change made by hand is seen")
    void reloadsAfterTheTimeToLive() {
        var clock = new MovableClock();
        var repo = mock(RoleRepository.class);
        var owner = role(1, "OWNER");
        var manager = role(3, "MANAGER");
        when(repo.findAll()).thenReturn(List.of(owner));
        var cache = new RoleCodeCache(repo, clock);

        cache.codesById();
        clock.advance(java.time.Duration.ofMinutes(9));
        cache.codesById();
        verify(repo, times(1)).findAll();

        clock.advance(java.time.Duration.ofMinutes(2));
        when(repo.findAll()).thenReturn(List.of(owner, manager));
        assertThat(cache.codesById()).containsEntry(3L, "MANAGER");
        verify(repo, times(2)).findAll();
    }

    @Test
    @DisplayName("an empty result is not kept, so roles still being seeded cannot deny everything for ten minutes")
    void emptyIsNotCached() {
        var repo = mock(RoleRepository.class);
        var owner = role(1, "OWNER");
        when(repo.findAll()).thenReturn(List.of());
        var cache = new RoleCodeCache(repo, new MovableClock());

        assertThat(cache.codesById()).isEmpty();
        when(repo.findAll()).thenReturn(List.of(owner));

        assertThat(cache.codesById()).isEqualTo(Map.of(1L, "OWNER"));
    }

    @Test
    @DisplayName("invalidate forces a reload")
    void invalidateReloads() {
        var repo = mock(RoleRepository.class);
        var owner = role(1, "OWNER");
        when(repo.findAll()).thenReturn(List.of(owner));
        var cache = new RoleCodeCache(repo, new MovableClock());
        cache.codesById();

        cache.invalidate();
        cache.codesById();

        verify(repo, times(2)).findAll();
    }

    @Test
    @DisplayName("the map handed out cannot be changed by a caller")
    void immutable() {
        var repo = mock(RoleRepository.class);
        var owner = role(1, "OWNER");
        when(repo.findAll()).thenReturn(List.of(owner));
        var cache = new RoleCodeCache(repo, new MovableClock());

        var codes = cache.codesById();

        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> codes.put(9L, "HACKER"));
    }

    @Test
    @DisplayName("a grant naming a role added after the load is seen at once, not after the TTL")
    void unknownRoleReloads() {
        var repo = mock(RoleRepository.class);
        var owner = role(1, "OWNER");
        var viewer = role(3, "CREDIT_VIEWER");
        when(repo.findAll()).thenReturn(List.of(owner), List.of(owner, viewer));
        var cache = new RoleCodeCache(repo, new MovableClock());

        assertThat(cache.codeOf(1L)).isEqualTo("OWNER");
        assertThat(cache.codeOf(3L)).isEqualTo("CREDIT_VIEWER");
        assertThat(cache.codeOf(3L)).isEqualTo("CREDIT_VIEWER");
        verify(repo, times(2)).findAll();
    }
}
