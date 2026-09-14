package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * The idempotent wrapper around recording a repayment.
 *
 * <p>A separate bean from {@link CreditInvoiceService} for the reason D-016 spells
 * out: the work must run inside {@code IdempotencyService.execute}, and a
 * {@code @Transactional} method invoked from inside that lambda on the <em>same</em>
 * bean would bypass its own proxy and run with no transaction at all. Mirrors
 * {@code SupplierOrderService} wrapping {@code SupplierOrderTransitions}.
 *
 * <p>Money leaving twice is the failure this prevents: a retried repayment that
 * reduced the outlet's utilization a second time would hand them credit they never
 * repaid.
 */
@Service
@RequiredArgsConstructor
public class CreditRepaymentService {

    private final IdempotencyService idempotency;
    private final CreditInvoiceService invoices;

    public CreditDtos.PaymentResponse record(Long actorId, Long invoiceId,
                                             CreditDtos.RecordPaymentRequest request,
                                             String idempotencyKey) {

        return idempotency.execute(actorId, "credit.repayment", idempotencyKey,
                Map.of("invoiceId", invoiceId, "amount", request.amount()),
                CreditDtos.PaymentResponse.class,
                () -> invoices.recordPayment(actorId, invoiceId, request, idempotencyKey));
    }
}
