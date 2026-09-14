package com.costonomy.mp.credit.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.credit.domain.CreditReservationStatus;
import com.costonomy.mp.credit.repository.CreditReservationRepository;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.service.OrderFundingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

/**
 * Funds orders with supplier credit. Implements procurement's {@link OrderFundingPort}.
 *
 * <p>Credit is this method's equivalent of an authorisation, and guardrail 16
 * applies identically: the supplier sees nothing until the credit is held. The
 * difference from prepaid is only in timing — a reservation succeeds or fails
 * inside the submission itself, so there is no checkout for the customer to
 * complete and no intent to hand back, and the orders are ready to release the
 * moment this returns.
 *
 * <p><b>A failed reservation is not an exception here.</b> It leaves the order in
 * {@code DRAFT}, unsecured, and {@code OrderReleaseService} then abandons it
 * exactly as it abandons an order whose card was declined — same path, same
 * outcome, one place that decides what an unfunded order means.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CreditFundingAdapter implements OrderFundingPort {

    private final CreditLedgerService ledger;
    private final CreditAgreementService agreements;
    private final CreditReservationRepository reservations;

    @Override
    public String paymentMethod() {
        return "CREDIT";
    }

    @Override
    @Transactional
    public List<FundingIntent> arrangeFunding(List<SupplierOrder> orders) {
        for (SupplierOrder order : orders) {
            var agreement = agreements.fundingAgreement(
                    order.getOutletId(), order.getSupplierStoreId());

            if (agreement == null) {
                // No agreement at all. Thrown rather than reserved-and-failed,
                // because there is nothing to reserve against and no ledger this
                // could belong to — and because the restaurant can fix it by
                // asking the supplier for terms.
                throw new BusinessException(ErrorCode.CREDIT_AGREEMENT_NOT_ACTIVE,
                        "You don't have credit with one of these suppliers yet.");
            }

            var reservation = ledger.reserve(agreement.getId(), order.getId(),
                    order.getProcurementId(), order.getTotalAmount());

            if (reservation.getStatus() != CreditReservationStatus.RESERVED) {
                // Reported with the specific wall that was hit — suspended, over
                // the limit, or above the per-order cap — because each has a
                // different answer, and "credit declined" has none.
                throw new BusinessException(
                        ErrorCode.valueOf(reservation.getFailureCode()),
                        reservation.getFailureReason());
            }
        }

        // Nothing for the client to do. Credit is already secured, so the submit
        // response carries no intents and the orders release immediately.
        return List.of();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFundingSecured(Long supplierOrderId) {
        return reservations.findBySupplierOrderId(supplierOrderId)
                .map(reservation -> reservation.getStatus().holdsExposure()
                        || reservation.getStatus() == CreditReservationStatus.UTILIZED)
                .orElse(false);
    }

    @Override
    @Transactional
    public void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount) {
        // Draws the accepted value, returns the rest, and raises the invoice that
        // starts the credit period running (doc 01 §19).
        ledger.utilize(supplierOrderId, acceptedAmount);
    }

    @Override
    @Transactional
    public void onOrderUnfulfilled(Long supplierOrderId, String reason) {
        ledger.release(supplierOrderId, reason);
    }
}
