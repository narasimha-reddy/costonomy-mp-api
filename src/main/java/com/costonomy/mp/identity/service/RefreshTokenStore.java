package com.costonomy.mp.identity.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.identity.domain.RefreshTokenStatus;
import com.costonomy.mp.identity.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Session revocation that must survive the rejection that triggers it.
 *
 * <p>Replay detection ends by throwing {@code UNAUTHENTICATED} — the replayed
 * token is refused. But the <em>response</em> to a replay is to revoke every
 * session for that user, and if that revocation shared the request transaction,
 * the throw would roll it back. The result looked correct from the outside (the
 * replay was rejected) while silently doing nothing: the attacker's stolen
 * session, or the victim's, stayed live for another thirty days.
 *
 * <p>{@code AuthFlowIT.replayRevokesAllSessions} catches exactly that, by checking
 * the <em>other</em> token stops working rather than only the replayed one.
 *
 * <p>{@code REQUIRES_NEW} only takes effect because this is a separate bean — a
 * self-invoked transactional method does not pass through Spring's proxy. Same
 * reason as {@code IdempotencyStore} and {@link OtpAttemptStore}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RefreshTokenStore {

    private final RefreshTokenRepository repository;
    private final AuditService auditService;

    /**
     * Revoke every live session for a user, committed independently.
     *
     * <p>Used on replay detection and on suspension. Doc 09 §1 requires
     * revocation, so a suspended user must lose access at the point of suspension
     * rather than when their refresh token happens to expire.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(Long userId, String reason) {
        var active = repository.findByUserIdAndStatus(userId, RefreshTokenStatus.ACTIVE);
        Instant now = Instant.now();

        active.forEach(token -> {
            token.setStatus(RefreshTokenStatus.REVOKED);
            token.setRevokedAt(now);
            token.setRevokedReason(reason);
        });
        repository.saveAll(active);

        auditService.record(userId, null, "SESSIONS_REVOKED", "USER", userId,
                null, null, reason, "AUTH");

        return active.size();
    }
}
