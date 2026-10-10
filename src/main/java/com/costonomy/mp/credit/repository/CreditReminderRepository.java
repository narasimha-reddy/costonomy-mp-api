package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditReminder;
import com.costonomy.mp.credit.domain.CreditReminderKind;
import com.costonomy.mp.credit.domain.CreditReminderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CreditReminderRepository extends JpaRepository<CreditReminder, Long> {

    Optional<CreditReminder> findByIdempotencyKey(String idempotencyKey);

    Page<CreditReminder> findByCreditAgreementIdOrderByIdDesc(Long creditAgreementId, Pageable pageable);

    /** The line's manual reminders asked for since {@code since}, oldest first: what the 24 hour and 7 day limits count. */
    List<CreditReminder> findByCreditAgreementIdAndKindAndRequestedAtGreaterThanOrderByRequestedAtAsc(
            Long creditAgreementId, CreditReminderKind kind, Instant since);

    /** How many manual reminders the store's lines were sent in a window: the per-store, per-India-day limit. */
    long countBySupplierStoreIdAndKindAndRequestedAtGreaterThanEqualAndRequestedAtLessThan(
            Long supplierStoreId, CreditReminderKind kind, Instant from, Instant to);

    List<CreditReminder> findByStatusAndRequestedAtLessThanEqualOrderByIdAsc(CreditReminderStatus status, Instant until);
}
