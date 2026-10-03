package com.costonomy.mp.delivery.repository;

import com.costonomy.mp.delivery.domain.DeliveryFeeQuote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DeliveryFeeQuoteRepository extends JpaRepository<DeliveryFeeQuote, Long> {

    Optional<DeliveryFeeQuote> findByReference(String reference);
}
