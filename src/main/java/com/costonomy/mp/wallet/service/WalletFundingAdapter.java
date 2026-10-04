package com.costonomy.mp.wallet.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.procurement.domain.SupplierOrder;
import com.costonomy.mp.procurement.service.OrderFundingPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Funds orders from the outlet's prepaid balance.
 *
 * <p>Shaped like {@code CreditFundingAdapter} rather than the prepaid one: the
 * money is already the restaurant's, so it moves inside the creating
 * transaction and there is no checkout to hand back. The order is ready to
 * release the moment this returns.
 *
 * <p><b>Guardrail 16 is unchanged.</b> A wallet that cannot cover the order
 * leaves it {@code DRAFT} and unfunded, and {@code OrderReleaseService} abandons
 * it exactly as it abandons a declined card — same path, same outcome, one place
 * that decides what an unfunded order means.
 *
 * <p>The refusal is thrown rather than returned quietly, because unlike a card
 * there is something the restaurant can do about it: top the wallet up, or pay
 * another way. "Not enough in your wallet" with the two figures is actionable;
 * an order that silently failed to fund is not.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WalletFundingAdapter implements OrderFundingPort {

    private final WalletService wallets;

    @Override
    public String paymentMethod() {
        return "WALLET";
    }

    @Override
    @Transactional
    public List<FundingIntent> arrangeFunding(List<SupplierOrder> orders) {
        for (SupplierOrder order : orders) {
            boolean paid = wallets.debitFor(
                    order.getOutletId(), order.getId(), order.getTotalAmount());

            if (!paid) {
                // Both figures, because the useful question is how short it is.
                // Rounded to the scale money is written in: a balance rendered
                // as 18904.8600 reads as a bug in the middle of a refusal.
                BigDecimal balance = wallets.balanceOf(order.getOutletId());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        ("Your wallet has ₹%s and this order comes to ₹%s. "
                                + "Top up, or pay another way.")
                                .formatted(rupees(balance), rupees(order.getTotalAmount())));
            }
        }

        // Nothing for the client to do: the money has already moved.
        return List.of();
    }

    private static String rupees(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFundingSecured(Long supplierOrderId) {
        return wallets.isPaid(supplierOrderId);
    }

    @Override
    @Transactional
    public void onOrderAccepted(Long supplierOrderId, BigDecimal acceptedAmount) {
        // Nothing to capture. A card is authorised and then taken; a wallet
        // balance moved when the order was created, so this is already settled.
    }

    @Override
    @Transactional
    public void onOrderUnfulfilled(Long supplierOrderId, String reason) {
        wallets.refundFor(supplierOrderId, reason);
    }

    /** The wallet paid the accepted total up front; what a weighing took off it comes back now (D-124). */
    @Override
    @Transactional
    public void onOrderDispatched(Long supplierOrderId, BigDecimal finalPayable) {
        wallets.settleOrder(supplierOrderId, finalPayable);
    }

    @Override
    @Transactional
    public void reduceAfterDispatch(Long supplierOrderId, BigDecimal amount, BigDecimal newFinalPayable,
                                    String key, Long actorId, String reason) {
        wallets.creditOrderAdjustment(supplierOrderId, amount, key, reason);
    }

    @Override
    public java.util.Optional<String> paymentState(Long supplierOrderId) {
        return wallets.paymentState(supplierOrderId);
    }

    @Override
    public BigDecimal refundableToWallet(Long supplierOrderId) {
        return wallets.refundableForOrder(supplierOrderId);
    }

    /** A dispute refund on a wallet-paid order goes back into the wallet (D-104). */
    @Override
    public Long refundToWallet(Long supplierOrderId, BigDecimal amount, String key,
                               Long actorId, String note) {
        wallets.creditDisputeRefund(supplierOrderId, amount, key);
        return null;
    }
}
