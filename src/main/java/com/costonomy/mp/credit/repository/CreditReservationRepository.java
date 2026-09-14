package com.costonomy.mp.credit.repository;

import com.costonomy.mp.credit.domain.CreditReservation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CreditReservationRepository extends JpaRepository<CreditReservation, Long> {

    Optional<CreditReservation> findBySupplierOrderId(Long supplierOrderId);

    List<CreditReservation> findByProcurementId(Long procurementId);
}
