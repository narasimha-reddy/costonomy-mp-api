package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditAgreement;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

/**
 * The agreement row held until the transaction ends, for the moves that must not interleave: a supplier closing a
 * line, the offer-expiry job, and a restaurant accepting an offer (D-165, D-166). Whoever takes it reads the row as
 * it is NOW, so call it before anything else loads the agreement in the same transaction: a row already in the
 * persistence context is not refreshed by a locking query.
 *
 * <p>Lock order, with the rest of credit: claim, wallet, invoices ascending id, agreement. These moves take the
 * agreement alone.
 */
public interface CreditAgreementLockRepository extends JpaRepository<CreditAgreement, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from CreditAgreement a where a.id = :id")
    Optional<CreditAgreement> lockById(@Param("id") Long id);
}
