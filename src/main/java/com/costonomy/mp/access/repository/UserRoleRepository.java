package com.costonomy.mp.access.repository;

import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    /**
     * Every live grant for a user.
     *
     * <p>Fetched whole rather than filtered by scope in SQL: a user belongs to a
     * handful of organisations at most, so this is a small indexed read, and doing
     * the scope expansion in Java keeps the hierarchy rules
     * ({@link ScopeType#satisfyingScopes()}) in one readable place instead of
     * spread across a query.
     */
    List<UserRole> findByUserIdAndStatus(Long userId, String status);

    List<UserRole> findByScopeTypeAndScopeIdAndStatus(ScopeType scopeType, Long scopeId, String status);

    Optional<UserRole> findByUserIdAndRoleIdAndScopeTypeAndScopeId(
            Long userId, Long roleId, ScopeType scopeType, Long scopeId);
}
