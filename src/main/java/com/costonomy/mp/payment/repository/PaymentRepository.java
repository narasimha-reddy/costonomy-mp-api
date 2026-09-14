package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.Payment;
import com.costonomy.mp.payment.domain.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findBySupplierOrderId(Long supplierOrderId);

    Optional<Payment> findByProviderPaymentId(String providerPaymentId);

    Optional<Payment> findByProviderOrderId(String providerOrderId);

    List<Payment> findByProcurementId(Long procurementId);

    List<Payment> findByStatusIn(Collection<PaymentStatus> statuses);

    /**
     * Payments waiting to be captured. Read by the capture job.
     *
     * <p>Ordered oldest first: a capture that has been pending longest is the one
     * closest to the provider's authorisation window expiring, and an authorisation
     * that lapses before capture is money the supplier will never receive.
     */
    @Query("""
            select p from Payment p
            where p.status = com.costonomy.mp.payment.domain.PaymentStatus.CAPTURE_PENDING
            order by p.updatedAt asc
            """)
    List<Payment> findPendingCaptures();

    /**
     * Authorisations that were never resolved. Read by the reconciliation job
     * (doc 21, doc 38).
     *
     * <p>Catches doc 46's lost callback: the customer paid, the client vanished,
     * and nothing told us. Asking the provider is the only way to find out.
     */
    @Query("""
            select p from Payment p
            where p.status in (com.costonomy.mp.payment.domain.PaymentStatus.CREATED,
                               com.costonomy.mp.payment.domain.PaymentStatus.AUTHORIZED)
              and p.updatedAt < :staleBefore
            order by p.updatedAt asc
            """)
    List<Payment> findStale(@Param("staleBefore") Instant staleBefore);
}
