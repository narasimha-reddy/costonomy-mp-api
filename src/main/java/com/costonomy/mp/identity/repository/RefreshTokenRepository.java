package com.costonomy.mp.identity.repository;

import com.costonomy.mp.identity.domain.RefreshToken;
import com.costonomy.mp.identity.domain.RefreshTokenStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, Long> {

    /** Lookup by the SHA-256 of the presented token. */
    Optional<RefreshToken> findByTokenHash(String tokenHash);

    List<RefreshToken> findByUserIdAndStatus(Long userId, RefreshTokenStatus status);
}
