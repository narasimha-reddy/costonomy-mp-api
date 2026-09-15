package com.costonomy.mp.trust.repository;

import com.costonomy.mp.trust.domain.Receiving;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReceivingRepository extends JpaRepository<Receiving, Long> {

    Optional<Receiving> findBySupplierOrderId(Long supplierOrderId);
}
