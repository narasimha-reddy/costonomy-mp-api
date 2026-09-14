package com.costonomy.mp.payment.repository;

import com.costonomy.mp.payment.domain.PaymentWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentWebhookEventRepository extends JpaRepository<PaymentWebhookEvent, Long> {

    Optional<PaymentWebhookEvent> findByProviderAndProviderEventId(
            String provider, String providerEventId);

    List<PaymentWebhookEvent> findByPaymentIdOrderByCreatedAtAsc(Long paymentId);
}
