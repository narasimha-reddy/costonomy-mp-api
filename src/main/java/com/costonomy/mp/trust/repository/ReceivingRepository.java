package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.Receiving;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReceivingRepository extends JpaRepository<Receiving, Long> {

    Optional<Receiving> findBySupplierOrderId(Long supplierOrderId);

    /** As above, locked: a duplicate check-in waits for the first and then finds it (D-129). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Receiving r where r.supplierOrderId = :orderId")
    Optional<Receiving> lockBySupplierOrderId(@Param("orderId") Long supplierOrderId);
}
