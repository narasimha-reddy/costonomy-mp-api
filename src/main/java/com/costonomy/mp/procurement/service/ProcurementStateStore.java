package com.costonomy.mp.procurement.service;

import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.procurement.domain.ProcurementStatus;
import com.costonomy.mp.procurement.repository.ProcurementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Procurement state changes that must outlive the exception reporting them.
 *
 * <p>When submission fails operationally — a supplier went offline in the seconds
 * between validating and submitting — doc 03 §4 wants the procurement marked
 * {@code FAILED} so the restaurant can see what happened and retry. But the way
 * that failure reaches the client is a thrown {@code BusinessException}, and a
 * throw rolls back the transaction that wrote the status. The order would appear
 * untouched and still {@code READY}, contradicting the error the restaurant was
 * just shown.
 *
 * <p>So it is written in its own committed transaction. {@code REQUIRES_NEW} only
 * works from a <b>separate bean</b> — a self-invoked transactional method does not
 * pass through Spring's proxy.
 *
 * <p>This is the fourth place in the codebase that needs this shape
 * ({@code IdempotencyStore}, {@code OtpAttemptStore}, {@code RefreshTokenStore},
 * and here). The rule worth carrying: <b>if a side effect has to survive the
 * exception that reports it, it needs its own transaction in its own bean.</b>
 */
@Service
@RequiredArgsConstructor
public class ProcurementStateStore {

    private final ProcurementRepository procurements;
    private final AuditService auditService;

    /** Mark a procurement failed, committed independently of the caller's rollback. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long procurementId, String reason, Long actorId) {
        procurements.findById(procurementId).ifPresent(procurement -> {
            var current = procurement.getStatus();
            if (!current.canTransitionTo(ProcurementStatus.FAILED)) {
                return;
            }
            procurement.setStatus(ProcurementStatus.FAILED);
            procurements.save(procurement);

            auditService.record(actorId, null, "PROCUREMENT_SUBMISSION_FAILED", "PROCUREMENT",
                    procurementId, current.name(), ProcurementStatus.FAILED.name(), reason, "API");
        });
    }
}
