package com.costonomy.mp.wallet.invoice.repository;

import com.costonomy.mp.wallet.invoice.domain.InvoiceStatus;
import com.costonomy.mp.wallet.invoice.domain.WalletEntryInvoice;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WalletEntryInvoiceRepository extends JpaRepository<WalletEntryInvoice, Long> {

    Optional<WalletEntryInvoice> findByWalletTransactionId(Long walletTransactionId);

    List<WalletEntryInvoice> findByWalletTransactionIdIn(Collection<Long> walletTransactionIds);

    long countByOutletIdAndCreatedAtGreaterThanEqual(Long outletId, Instant since);

    /**
     * Still being read and due: not tried since {@code before}, or, after the cost app was unavailable, its
     * {@code nextTryAt} has come (D-115). The retry job's work.
     */
    @Query("""
            select i.id from WalletEntryInvoice i
             where i.status = :status
               and ((i.nextTryAt is null and coalesce(i.lastAttemptAt, i.createdAt) < :before)
                    or (i.nextTryAt is not null and i.nextTryAt <= :now))
             order by i.id
            """)
    List<Long> dueForReading(@Param("status") InvoiceStatus status, @Param("before") Instant before,
                             @Param("now") Instant now, Pageable page);
}
