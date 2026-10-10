package com.costonomy.mp.intent.service;

import com.costonomy.mp.intent.domain.Intent;
import com.costonomy.mp.intent.domain.IntentStatus;
import com.costonomy.mp.intent.repository.IntentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Inserts a draft in a transaction of its own (D-137, D-021).
 *
 * <p>The database allows one draft per outlet and store. When two first additions race, the loser's insert fails with
 * a duplicate key. In the caller's transaction that failure would poison it; here it only rolls back this
 * {@code REQUIRES_NEW} transaction, and the caller then locks the winner's draft and carries on. A separate bean, so
 * the proxy applies.
 */
@Component
@RequiredArgsConstructor
class IntentDraftStore {

    private static final DateTimeFormatter REFERENCE_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final IntentRepository intents;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long insertDraft(Long actorId, Long outletId, Long supplierStoreId, String source, Long clonedFromId) {
        var intent = new Intent();
        intent.setOutletId(outletId);
        intent.setSupplierStoreId(supplierStoreId);
        intent.setCreatedBy(actorId);
        intent.setStatus(IntentStatus.DRAFT);
        intent.setSource(source);
        intent.setClonedFromId(clonedFromId);
        // Placeholder for one statement: the reference embeds the id, which is not known before the insert. Unique
        // per call, because a shared placeholder makes concurrent inserts queue on one unique-index entry.
        intent.setReference("TMP-" + java.util.UUID.randomUUID().toString().substring(0, 23));
        intents.saveAndFlush(intent);

        intent.setReference("RQ-%s-%06d".formatted(
                LocalDate.now(ZoneOffset.UTC).format(REFERENCE_DATE), intent.getId()));
        intents.saveAndFlush(intent);
        return intent.getId();
    }

    /**
     * The current draft's id, read in a transaction of its own so it sees the latest committed rows (the caller's
     * snapshot may predate them) and takes no locks, unlike a {@code FOR UPDATE} range scan.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public java.util.Optional<Long> currentDraftId(Long outletId, Long supplierStoreId) {
        return intents.findDraftId(outletId, supplierStoreId);
    }
}
