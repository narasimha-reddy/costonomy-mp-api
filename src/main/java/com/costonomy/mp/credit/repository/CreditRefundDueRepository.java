package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditRefundDue;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CreditRefundDueRepository extends JpaRepository<CreditRefundDue, Long> {

    Optional<CreditRefundDue> findByIdempotencyKey(String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from CreditRefundDue r where r.id = :id")
    Optional<CreditRefundDue> lockById(@Param("id") Long id);

    List<CreditRefundDue> findBySupplierStoreIdOrderByIdDesc(Long supplierStoreId);

    List<CreditRefundDue> findBySupplierStoreIdAndStatusOrderByIdDesc(Long supplierStoreId, CreditRefundDue.Status status);

    List<CreditRefundDue> findByCreditInvoiceIdOrderByIdAsc(Long creditInvoiceId);

    boolean existsByCreditInvoiceId(Long creditInvoiceId);
}
