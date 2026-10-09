package com.costonomy.mp.credit.service;

import com.costonomy.mp.access.domain.Permissions;
import com.costonomy.mp.access.domain.ScopeType;
import com.costonomy.mp.access.service.AccessControlService;
import com.costonomy.mp.common.audit.AuditService;
import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.common.idempotency.IdempotencyService;
import com.costonomy.mp.common.logging.TraceScope;
import com.costonomy.mp.common.text.Rupees;
import com.costonomy.mp.credit.domain.CreditAgreement;
import com.costonomy.mp.credit.domain.CreditInvoice;
import com.costonomy.mp.credit.domain.CreditInvoiceStatus;
import com.costonomy.mp.credit.domain.CreditPayChannel;
import com.costonomy.mp.credit.domain.CreditPayInvoiceOutcome;
import com.costonomy.mp.credit.domain.CreditPayMethod;
import com.costonomy.mp.credit.domain.CreditPayRefundStatus;
import com.costonomy.mp.credit.domain.CreditPaymentAttempt;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptInvoice;
import com.costonomy.mp.credit.domain.CreditPaymentAttemptStatus;
import com.costonomy.mp.credit.repository.CreditAgreementRepository;
import com.costonomy.mp.credit.repository.CreditExposureStore;
import com.costonomy.mp.credit.repository.CreditInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentAttemptInvoiceRepository;
import com.costonomy.mp.credit.repository.CreditPaymentAttemptRepository;
import com.costonomy.mp.credit.repository.CreditPaymentRefundRepository;
import com.costonomy.mp.credit.web.dto.CreditDtos;
import com.costonomy.mp.credit.web.dto.CreditPayDtos;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.wallet.service.WalletService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A restaurant pays whole credit invoices, across suppliers, online (UPI, card) or from its wallet, and the
 * suppliers are told, not asked (D-196).
 *
 * <p>The app sends invoice ids and the {@code outstanding} it was shown, as a guard, and never an amount: the server
 * computes every total, fee and shortfall. Credit stays the supplier's: Mandi never funds or guarantees it.
 *
 * <p>It is a plain bean around a {@link TransactionTemplate} (READ COMMITTED, like {@code WalletTopUpService}), not a
 * {@code @Transactional} method, because the work runs inside {@code IdempotencyService.execute} (D-016) and because
 * <b>no Razorpay call holds a transaction or a connection</b> (D-099): an online payment is a short transaction that
 * writes the attempt, then Razorpay is asked, then a short transaction attaches the order.
 *
 * <p>The online capture is applied by {@link CreditPaySettlement}, not here. The wallet method is applied here, in one
 * transaction, in the lock order wallet, invoices ascending id, agreements ascending id (the attempt row is inserted,
 * not locked, after the wallet lock).
 */
@Service
@Slf4j
public class CreditPayService {

    static final BigDecimal MIN_ONLINE_AMOUNT = new BigDecimal("1.00");
    private static final List<CreditInvoiceStatus> SETTLED = List.of(CreditInvoiceStatus.PAID, CreditInvoiceStatus.WRITTEN_OFF);
    private static final int MAX_REFUND_ATTEMPTS = 5;

    private final CreditPaymentAttemptRepository attempts;
    private final CreditPaymentAttemptInvoiceRepository attemptInvoices;
    private final CreditPaymentRefundRepository refunds;
    private final CreditInvoiceRepository invoices;
    private final CreditAgreementRepository agreements;
    private final CreditInvoiceService invoiceService;
    private final CreditRepaymentApplier applier;
    private final CreditPaySettlement settlement;
    private final CreditExposureStore exposure;
    private final CreditDirectory directory;
    private final WalletService wallet;
    private final PaymentProvider provider;
    private final AccessControlService accessControl;
    private final IdempotencyService idempotency;
    private final AuditService audit;
    private final TransactionTemplate tx;
    // Field-injected: a constructor would drop the @Qualifier, and there must be no unqualified Clock here.
    @Autowired
    @Qualifier("creditClock")
    private Clock clock;

    @Value("${costonomy.mp.credit.online-pay.enabled:false}")
    private boolean onlineEnabled;
    @Value("${costonomy.mp.credit.wallet-repay.enabled:false}")
    private boolean walletEnabled;
    @Value("${costonomy.mp.credit.online-pay.fee-percent:0}")
    private BigDecimal feePercent;
    @Value("${costonomy.mp.credit.online-pay.max-invoices:50}")
    private int maxInvoices;
    @Value("${costonomy.mp.credit.online-pay.expire-after:PT30M}")
    private Duration expireAfter;

    public CreditPayService(CreditPaymentAttemptRepository attempts,
                            CreditPaymentAttemptInvoiceRepository attemptInvoices,
                            CreditPaymentRefundRepository refunds, CreditInvoiceRepository invoices,
                            CreditAgreementRepository agreements, CreditInvoiceService invoiceService,
                            CreditRepaymentApplier applier, CreditPaySettlement settlement,
                            CreditExposureStore exposure, CreditDirectory directory, WalletService wallet,
                            PaymentProvider provider, AccessControlService accessControl,
                            IdempotencyService idempotency, AuditService audit,
                            PlatformTransactionManager transactionManager) {
        this.attempts = attempts;
        this.attemptInvoices = attemptInvoices;
        this.refunds = refunds;
        this.invoices = invoices;
        this.agreements = agreements;
        this.invoiceService = invoiceService;
        this.applier = applier;
        this.settlement = settlement;
        this.exposure = exposure;
        this.directory = directory;
        this.wallet = wallet;
        this.provider = provider;
        this.accessControl = accessControl;
        this.idempotency = idempotency;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /** A fee outside 0 to 10 percent is a typo that would charge restaurants a surprise: refuse to start. */
    @PostConstruct
    void checkFee() {
        if (feePercent == null || feePercent.signum() < 0 || feePercent.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalStateException("costonomy.mp.credit.online-pay.fee-percent must be between 0 and 10, was "
                    + feePercent);
        }
    }

    // ── Reads: the picker and the quote ──────────────────────────────────

    public CreditPayDtos.PayableResponse payable(Long actorId, Long outletId) {
        requireOnline();
        requireOutlet(actorId, outletId);
        LocalDate today = invoiceService.today();

        var open = invoices.findOpenOfOutlet(outletId, SETTLED).stream()
                .filter(i -> i.outstanding().signum() > 0).toList();
        var byAgreement = open.stream().collect(Collectors.groupingBy(CreditInvoice::getCreditAgreementId));
        var agreementById = agreements.findAllById(byAgreement.keySet()).stream()
                .collect(Collectors.toMap(CreditAgreement::getId, a -> a));

        var suppliers = new ArrayList<CreditPayDtos.PayableSupplier>();
        for (Long agreementId : orderAgreements(byAgreement, today)) {
            var agreement = agreementById.get(agreementId);
            if (agreement == null) {
                continue;
            }
            var store = directory.store(agreement.getSupplierStoreId());
            boolean payable = store != null && store.tradeable();
            var claims = invoiceService.openClaimsByInvoice(agreementId);
            var dues = invoiceService.duesFor(agreementId);
            var rows = byAgreement.get(agreementId).stream()
                    .sorted(Comparator.comparing((CreditInvoice i) -> !isLate(i, today))
                            .thenComparing(CreditInvoice::getDueDate).thenComparing(CreditInvoice::getId))
                    .map(i -> toPayableInvoice(i, claims.get(i.getId()), today)).toList();
            suppliers.add(new CreditPayDtos.PayableSupplier(agreementId, agreement.getSupplierStoreId(),
                    store == null ? null : store.supplierName(), store == null ? null : store.storeName(),
                    agreement.getStatus(), payable, payable ? null : "Pay this supplier directly.",
                    dues.due(), dues.overdue(), rows));
        }
        var methods = new ArrayList<CreditPayMethod>();
        if (onlineEnabled) {
            methods.add(CreditPayMethod.UPI);
            methods.add(CreditPayMethod.CARD);
        }
        if (walletMethodOn()) {
            methods.add(CreditPayMethod.WALLET);
        }
        return new CreditPayDtos.PayableResponse(outletId, onlineEnabled, walletMethodOn(), methods,
                feePercent, MIN_ONLINE_AMOUNT, maxInvoices, suppliers);
    }

    public CreditPayDtos.QuoteResponse quote(Long actorId, Long outletId, CreditPayDtos.QuoteRequest request) {
        requireOnline();
        requireOutlet(actorId, outletId);
        var items = validItems(request == null ? null : request.items());
        var checked = checkSelection(outletId, items, false);

        BigDecimal fee = feeOn(checked.amount());
        BigDecimal online = checked.amount().add(fee);
        var walletRow = wallet.find(outletId).orElse(null);
        BigDecimal balance = walletRow == null ? null : walletRow.getBalance();

        var methods = new ArrayList<CreditPayDtos.MethodQuote>();
        for (var method : List.of(CreditPayMethod.UPI, CreditPayMethod.CARD)) {
            String why = !onlineEnabled ? "ONLINE_OFF" : online.compareTo(MIN_ONLINE_AMOUNT) < 0 ? "BELOW_MINIMUM" : null;
            methods.add(new CreditPayDtos.MethodQuote(method, why == null, why, fee, online, null));
        }
        String walletWhy = null;
        BigDecimal shortBy = null;
        if (!walletMethodOn()) {
            walletWhy = "WALLET_OFF";
        } else if (walletRow != null && !walletRow.isUsable()) {
            walletWhy = "WALLET_ON_HOLD";
        } else if ((balance == null ? BigDecimal.ZERO : balance).compareTo(checked.amount()) < 0) {
            walletWhy = "WALLET_SHORT";
            shortBy = checked.amount().subtract(balance == null ? BigDecimal.ZERO : balance);
        }
        methods.add(new CreditPayDtos.MethodQuote(CreditPayMethod.WALLET, walletWhy == null, walletWhy,
                BigDecimal.ZERO, checked.amount(), shortBy));
        return new CreditPayDtos.QuoteResponse(checked.invoices().size(),
                (int) checked.invoices().stream().map(CreditInvoice::getCreditAgreementId).distinct().count(),
                checked.amount(), balance, methods);
    }

    public CreditPayDtos.AttemptResponse get(Long actorId, Long outletId, Long attemptId) {
        requireOutlet(actorId, outletId);
        return toResponse(attemptOf(outletId, attemptId), false, false);
    }

    // ── Create ───────────────────────────────────────────────────────────

    public CreditPayDtos.AttemptResponse create(Long actorId, Long outletId, CreditPayDtos.CreateRequest request,
                                                String idempotencyKey) {
        requireOnline();
        requireOutlet(actorId, outletId);
        var method = request == null ? null : request.method();
        if (method == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose how to pay.");
        }
        if (method == CreditPayMethod.WALLET && !walletEnabled) {
            // Before anything is read or claimed: a refusal here leaves no trace, not even an idempotency key.
            throw new BusinessException(ErrorCode.FORBIDDEN, "Paying from your wallet isn't available yet.");
        }
        var items = validItems(request.items());
        var channel = method.channel();

        // UPI and CARD are one channel: changing between them with the same key replays, because the method only
        // pre-selects Razorpay's checkout.
        var payload = Map.<String, Object>of("outletId", outletId, "channel", channel.name(),
                "items", items.stream()
                        .sorted(Comparator.comparing(CreditPayDtos.PayItem::invoiceId))
                        .map(i -> Map.of("invoiceId", i.invoiceId(), "outstanding", i.outstanding().setScale(2).toPlainString()))
                        .toList());
        try (var trace = TraceScope.of("outlet", outletId)) {
            return idempotency.execute(actorId, "credit.pay.create", idempotencyKey, payload,
                    CreditPayDtos.AttemptResponse.class,
                    () -> channel == CreditPayChannel.WALLET
                            ? createFromWallet(actorId, outletId, items, idempotencyKey)
                            : createOnline(actorId, outletId, method, items, idempotencyKey));
        }
    }

    /** UPI or card: the attempt row, then Razorpay (no transaction open), then the order id on the row. */
    private CreditPayDtos.AttemptResponse createOnline(Long actorId, Long outletId, CreditPayMethod method,
                                                       List<CreditPayDtos.PayItem> items, String clientKey) {
        var checked = checkSelection(outletId, items, false);
        BigDecimal fee = feeOn(checked.amount());
        BigDecimal total = checked.amount().add(fee);
        if (total.compareTo(MIN_ONLINE_AMOUNT) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Online payments start at ₹1. Pay this from your wallet.");
        }
        String rowKey = "outlet-" + outletId + "-" + clientKey;
        var sameKey = attempts.findByIdempotencyKey(rowKey);
        if (sameKey.isPresent()) {
            return replay(sameKey.get(), checked);
        }

        // One open attempt per selection: a second tap reopens it, as long as nothing in it has changed.
        String openKey = "outlet-" + outletId + "-ONLINE-" + sha256Hex(checked.invoices().stream()
                .map(i -> i.getId().toString()).collect(Collectors.joining(",")));
        var open = attempts.findByOpenSelectionKey(openKey);
        if (open.isPresent()) {
            var found = open.get();
            if (found.getRazorpayOrderId() == null) {
                throw startingConflict();
            }
            if (unchanged(found, checked)) {
                return toResponse(found, true, true);
            }
            // The selection moved since that attempt was opened: it ends, a new one starts. If it was applied in the
            // meantime the conditional update does nothing and the key is already free.
            tx.executeWithoutResult(status -> attempts.endFromCreated(found.getId(), CreditPaymentAttemptStatus.EXPIRED,
                    "Selection changed", Instant.now()));
        }

        CreditPaymentAttempt row;
        try {
            row = tx.execute(status -> {
                var created = newAttempt(actorId, outletId, method, checked, fee, total, rowKey);
                created.setOpenSelectionKey(openKey);
                attempts.saveAndFlush(created);
                attemptInvoices.saveAllAndFlush(rowsOf(created.getId(), checked));
                return created;
            });
        } catch (DataIntegrityViolationException raced) {
            // The same key (or the same selection) arriving twice at once: the unique keys decided; answer with the winner.
            var byKey = attempts.findByIdempotencyKey(rowKey);
            if (byKey.isPresent()) {
                return replay(byKey.get(), checked);
            }
            var winner = attempts.findByOpenSelectionKey(openKey).orElseThrow(() -> raced);
            if (winner.getRazorpayOrderId() != null && unchanged(winner, checked)) {
                return toResponse(winner, true, true);
            }
            throw startingConflict();
        }

        try (var trace = TraceScope.of("credit_payment_attempt", row.getId(), "outlet", outletId)) {
            PaymentProvider.AuthorizationIntent intent;
            try {
                intent = provider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                        // Both derived from the row: a retry of this call reaches the same intent at the provider.
                        "creditpay-" + row.getId(), total, "INR", "Mandi credit payment",
                        "creditpay-" + row.getId(), true));
            } catch (RuntimeException ex) {
                // Nothing was payable: the client never received an order id.
                tx.executeWithoutResult(status -> attempts.endFromCreated(row.getId(), CreditPaymentAttemptStatus.FAILED,
                        truncate("Could not open a Razorpay order: " + ex.getMessage()), null));
                log.warn("Credit payment attempt {} FAILED: could not open a Razorpay order: {}", row.getId(), ex.getMessage());
                throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                        "We couldn't start the payment. Nothing was charged. Please try again.");
            }
            tx.executeWithoutResult(status -> attempts.attachOrder(row.getId(), intent.providerOrderId()));
            audit.record(actorId, null, "CREDIT_PAY_ATTEMPT_CREATED", "CREDIT_PAYMENT_ATTEMPT", row.getId(), null,
                    CreditPaymentAttemptStatus.CREATED.name(),
                    total.toPlainString() + " for " + items.size() + " invoice(s) via " + method, "API");
            log.info("Credit payment attempt {} created for {} ({} invoice(s)) with Razorpay order {}",
                    row.getId(), Rupees.of(total), items.size(), intent.providerOrderId());
            return toResponse(attempts.findById(row.getId()).orElseThrow(), true, false);
        }
    }

    /** The same key again: the same attempt if it is the same request and still payable. */
    private CreditPayDtos.AttemptResponse replay(CreditPaymentAttempt existing, Checked checked) {
        var rows = attemptInvoices.findByAttemptIdOrderByCreditInvoiceIdAsc(existing.getId());
        boolean same = existing.getAmount().compareTo(checked.amount()) == 0
                && rows.stream().map(CreditPaymentAttemptInvoice::getCreditInvoiceId).toList()
                .equals(checked.invoices().stream().map(CreditInvoice::getId).toList());
        if (!same) {
            throw new BusinessException(ErrorCode.IDEMPOTENCY_KEY_REUSE);
        }
        if (existing.getStatus() != CreditPaymentAttemptStatus.CREATED || existing.getRazorpayOrderId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "That payment has already ended. Start a new one.");
        }
        return toResponse(existing, true, false);
    }

    private static BusinessException startingConflict() {
        return new BusinessException(ErrorCode.CONCURRENT_MODIFICATION,
                "This payment is already being started. Try again in a moment.");
    }

    /** Does every invoice of the open attempt still owe exactly what it was charged for? */
    private boolean unchanged(CreditPaymentAttempt attempt, Checked checked) {
        var byId = checked.invoices().stream().collect(Collectors.toMap(CreditInvoice::getId, i -> i));
        var rows = attemptInvoices.findByAttemptIdOrderByCreditInvoiceIdAsc(attempt.getId());
        return rows.size() == byId.size() && rows.stream().allMatch(r -> {
            var invoice = byId.get(r.getCreditInvoiceId());
            return invoice != null && invoice.outstanding().compareTo(r.getExpectedAmount()) == 0;
        });
    }

    // ── Create, from the wallet ──────────────────────────────────────────

    /** One transaction. Lock order: wallet, invoices ascending id, agreements ascending id. */
    private CreditPayDtos.AttemptResponse createFromWallet(Long actorId, Long outletId,
                                                           List<CreditPayDtos.PayItem> items, String clientKey) {
        return tx.execute(status -> {
            // a. The wallet first, like QuickScan and the wallet repayment.
            var walletRow = wallet.lock(outletId);
            if (!walletRow.isUsable()) {
                throw new BusinessException(ErrorCode.WALLET_ON_HOLD);
            }
            // b. The invoices, ascending id, and the selection checked again now that nobody can change them.
            var checked = checkSelection(outletId, items, true);
            // c. The balance.
            BigDecimal balance = walletRow.getBalance();
            if (checked.amount().compareTo(balance) > 0) {
                BigDecimal shortBy = checked.amount().subtract(balance);
                throw new BusinessException(ErrorCode.WALLET_INSUFFICIENT_BALANCE,
                        "You're ₹%s short.".formatted(Rupees.of(shortBy)),
                        Map.of("shortBy", shortBy, "balance", balance));
            }
            // d. The attempt, inserted (not locked) after the wallet lock, already PAID: it applies synchronously.
            Instant now = Instant.now();
            var attempt = newAttempt(actorId, outletId, CreditPayMethod.WALLET, checked, BigDecimal.ZERO,
                    checked.amount(), "outlet-" + outletId + "-" + clientKey);
            attempt.setStatus(CreditPaymentAttemptStatus.PAID);
            attempt.setPaymentMethod("WALLET");
            attempt.setPaidAt(now);
            attempts.saveAndFlush(attempt);
            Long attemptId = attempt.getId();
            attemptInvoices.saveAllAndFlush(rowsOf(attemptId, checked));

            // e. One wallet debit per supplier (D-158 names the supplier on each entry), agreements ascending.
            var byAgreement = new TreeMap<Long, List<CreditInvoice>>();
            for (var invoice : checked.invoices()) {
                byAgreement.computeIfAbsent(invoice.getCreditAgreementId(), k -> new ArrayList<>()).add(invoice);
            }
            var repaymentByAgreement = new HashMap<Long, Long>();
            var paymentByInvoice = new HashMap<Long, Long>();
            for (var group : byAgreement.entrySet()) {
                var lines = group.getValue().stream()
                        .sorted(Comparator.comparing(CreditInvoice::getDueDate).thenComparing(CreditInvoice::getId))
                        .map(i -> new CreditRepaymentApplier.Line(i, i.outstanding())).toList();
                var applied = applier.apply(actorId, group.getKey(), outletId, group.getValue().get(0).getSupplierStoreId(),
                        lines, CreditRepaymentApplier.Funding.wallet(
                                "wallet-pay:" + attemptId + ":" + group.getKey(), "wallet-pay:", attemptId), now);
                repaymentByAgreement.put(group.getKey(), applied.repaymentId());
                paymentByInvoice.putAll(applied.paymentIdByInvoice());
            }
            // The debits clear the persistence context, so the rows are read again before they are changed.
            var rows = attemptInvoices.findByAttemptIdOrderByCreditInvoiceIdAsc(attemptId);
            for (var row : rows) {
                row.setOutcome(CreditPayInvoiceOutcome.PAID);
                row.setCreditRepaymentId(repaymentByAgreement.get(row.getCreditAgreementId()));
                row.setCreditPaymentId(paymentByInvoice.get(row.getCreditInvoiceId()));
            }
            attemptInvoices.saveAllAndFlush(rows);

            // f. Audit. No outlet event: the restaurant sees the result on screen. The suppliers' events come from the applier.
            audit.record(actorId, null, "CREDIT_PAY_ATTEMPT_PAID", "CREDIT_PAYMENT_ATTEMPT", attemptId, null,
                    CreditPaymentAttemptStatus.PAID.name(),
                    checked.amount().toPlainString() + " from the wallet for " + rows.size() + " invoice(s)", "API");
            log.info("Credit payment attempt {} of {} from the wallet of outlet {}: {} invoice(s)",
                    attemptId, Rupees.of(checked.amount()), outletId, rows.size());
            return toResponse(attempts.findById(attemptId).orElseThrow(), false, false, wallet.balanceOf(outletId));
        });
    }

    // ── Verify ───────────────────────────────────────────────────────────

    /**
     * The client says it has paid. Verify that, then apply, as {@code WalletTopUpService.confirm}: the signature
     * only shows the payment id came out of a checkout for this order; what the payment is, whose, for how much,
     * captured or not, is whatever Razorpay says when asked. Works with the flag off: money in flight still lands.
     */
    public CreditPayDtos.AttemptResponse verify(Long actorId, Long outletId, Long attemptId,
                                                CreditPayDtos.VerifyRequest request) {
        requireOutlet(actorId, outletId);
        var attempt = attemptOf(outletId, attemptId);
        String paymentId = request.razorpayPaymentId();
        try (var trace = TraceScope.of("credit_payment_attempt", attemptId, "outlet", outletId,
                "rzp_order", attempt.getRazorpayOrderId(), "rzp_payment", paymentId)) {
            log.info("Credit payment attempt {} verify requested", attemptId);
            if (attempt.getChannel() == CreditPayChannel.WALLET || attempt.getStatus() == CreditPaymentAttemptStatus.FAILED) {
                throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "That payment has ended. Start a new one.");
            }
            if (attempt.getRazorpayOrderId() == null) {
                throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "That payment was never opened.");
            }
            if (!provider.verifyCheckoutSignature(attempt.getRazorpayOrderId(), paymentId, request.razorpaySignature())) {
                audit.record(null, null, "CREDIT_PAY_VERIFY_REJECTED", "CREDIT_PAYMENT_ATTEMPT", attemptId,
                        attempt.getStatus().name(), attempt.getStatus().name(),
                        "Checkout signature did not verify for payment " + paymentId, "CLIENT");
                log.warn("Credit payment attempt {} verify rejected: the checkout signature did not verify", attemptId);
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "We couldn't verify that payment. Nothing was paid.");
            }
            if (attempt.getStatus() == CreditPaymentAttemptStatus.PAID
                    || attempt.getStatus() == CreditPaymentAttemptStatus.REFUNDED) {
                return toResponse(attempt, false, false);
            }

            PaymentProvider.ProviderPayment payment;
            try {
                payment = provider.fetchPayment(paymentId);
            } catch (PaymentProviderException ex) {
                if (ex.isRetryable()) {
                    // We could not ask. The money may well be captured; the webhook or the poller applies it.
                    throw new BusinessException(ErrorCode.CREDIT_PAY_PROCESSING);
                }
                log.warn("Credit payment attempt {} verify named a payment Razorpay refused: {}", attemptId, ex.getMessage());
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "We couldn't find that payment. Nothing was paid.");
            }

            switch (settlement.settle(attemptId, payment, "CONFIRM")) {
                case APPLIED, ALREADY_APPLIED, DUPLICATE_REFUNDING -> { }
                case PROCESSING -> throw new BusinessException(ErrorCode.CREDIT_PAY_PROCESSING);
                case DECLINED -> throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                        "That payment didn't go through. Nothing was charged.");
                case REJECTED -> throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "That payment doesn't match this payment request. Nothing was paid.");
            }
            return toResponse(attemptOf(outletId, attemptId), false, false);
        }
    }

    // ── The selection ────────────────────────────────────────────────────

    /** The invoices of a validated selection, the total, and who they are payable to. */
    private record Checked(List<CreditInvoice> invoices, BigDecimal amount) { }

    /**
     * Shared by quote, create and the wallet transaction. Missing (or another outlet's) invoice: 404. Any invoice
     * settled, or owing anything other than what the app was shown: 409 with the ids. A store that cannot be paid
     * through Mandi: 422. A move from ISSUED to OVERDUE, a due-date extension or a suspension is not a change.
     */
    private Checked checkSelection(Long outletId, List<CreditPayDtos.PayItem> items, boolean lock) {
        var sent = new LinkedHashMap<Long, BigDecimal>();
        items.stream().sorted(Comparator.comparing(CreditPayDtos.PayItem::invoiceId))
                .forEach(i -> sent.put(i.invoiceId(), i.outstanding()));
        var ids = new ArrayList<>(sent.keySet());
        var found = lock ? invoices.lockByOutletAndIds(outletId, ids) : invoices.findByOutletAndIds(outletId, ids);
        var byId = found.stream().collect(Collectors.toMap(CreditInvoice::getId, i -> i));
        for (Long id : ids) {
            if (!byId.containsKey(id)) {
                throw new NotFoundException("CreditInvoice", id);
            }
        }
        var changed = ids.stream().filter(id -> {
            var invoice = byId.get(id);
            return invoice.getStatus().isSettled() || invoice.outstanding().compareTo(sent.get(id)) != 0;
        }).toList();
        if (!changed.isEmpty()) {
            throw new BusinessException(ErrorCode.CREDIT_PAY_SELECTION_CHANGED, ErrorCode.CREDIT_PAY_SELECTION_CHANGED.defaultMessage(),
                    Map.of("changedInvoiceIds", changed));
        }
        var unavailable = new LinkedHashMap<Long, String>();
        for (var invoice : found) {
            var store = directory.store(invoice.getSupplierStoreId());
            if (store == null || !store.tradeable()) {
                unavailable.putIfAbsent(invoice.getSupplierStoreId(), store == null ? "this supplier" : store.supplierName());
            }
        }
        if (!unavailable.isEmpty()) {
            throw new BusinessException(ErrorCode.CREDIT_PAY_SUPPLIER_UNAVAILABLE,
                    "Pay %s directly. Online payment isn't available for them.".formatted(unavailable.values().iterator().next()),
                    Map.of("supplierStoreIds", new ArrayList<>(unavailable.keySet()),
                            "supplierNames", new ArrayList<>(unavailable.values())));
        }
        BigDecimal amount = found.stream().map(CreditInvoice::outstanding).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Checked(found, amount);
    }

    /** The shape rules of a selection; the same messages whichever endpoint it came to. */
    private List<CreditPayDtos.PayItem> validItems(List<CreditPayDtos.PayItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose at least one invoice.");
        }
        if (items.size() > maxInvoices) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose at most %d invoices at a time.".formatted(maxInvoices));
        }
        var seen = new HashSet<Long>();
        for (var item : items) {
            if (item == null || item.invoiceId() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Choose an invoice.");
            }
            if (!seen.add(item.invoiceId())) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Each invoice can be chosen only once.");
            }
            if (item.outstanding() == null || item.outstanding().signum() <= 0
                    || item.outstanding().stripTrailingZeros().scale() > 2) {
                throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                        "The outstanding amount must be more than zero, in rupees and paise.");
            }
        }
        return items;
    }

    private CreditPaymentAttempt newAttempt(Long actorId, Long outletId, CreditPayMethod method, Checked checked,
                                            BigDecimal fee, BigDecimal total, String rowKey) {
        var created = new CreditPaymentAttempt();
        created.setOutletId(outletId);
        created.setCreatedBy(actorId);
        created.setMethod(method);
        created.setChannel(method.channel());
        created.setStatus(CreditPaymentAttemptStatus.CREATED);
        created.setInvoiceCount(checked.invoices().size());
        created.setAmount(checked.amount());
        created.setFeePercent(method.channel() == CreditPayChannel.ONLINE ? feePercent : BigDecimal.ZERO);
        created.setFeeAmount(fee);
        created.setTotalCharged(total);
        created.setIdempotencyKey(rowKey);
        return created;
    }

    private List<CreditPaymentAttemptInvoice> rowsOf(Long attemptId, Checked checked) {
        return checked.invoices().stream().map(invoice -> {
            var row = new CreditPaymentAttemptInvoice();
            row.setAttemptId(attemptId);
            row.setCreditInvoiceId(invoice.getId());
            row.setCreditAgreementId(invoice.getCreditAgreementId());
            row.setSupplierStoreId(invoice.getSupplierStoreId());
            row.setExpectedAmount(invoice.outstanding());
            return row;
        }).toList();
    }

    /** fee = amount x percent / 100, half up to paise. Online only. */
    private BigDecimal feeOn(BigDecimal amount) {
        return amount.multiply(feePercent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    // ── Ordering ─────────────────────────────────────────────────────────

    /** Past its grace period, marked or not yet marked by the sweep. */
    private static boolean isLate(CreditInvoice invoice, LocalDate today) {
        return invoice.getStatus() == CreditInvoiceStatus.OVERDUE || today.isAfter(invoice.getOverdueAfter());
    }

    /**
     * Suppliers with something late first, by their oldest {@code overdueAfter}; then by earliest due date, then
     * agreement id. The one order the picker, the quote and the result show, and the app shows it as sent.
     */
    private List<Long> orderAgreements(Map<Long, List<CreditInvoice>> byAgreement, LocalDate today) {
        record Key(Long agreementId, boolean late, LocalDate oldestLate, LocalDate earliestDue) { }
        var keys = new ArrayList<Key>();
        for (var entry : byAgreement.entrySet()) {
            var late = entry.getValue().stream().filter(i -> isLate(i, today)).map(CreditInvoice::getOverdueAfter)
                    .min(Comparator.naturalOrder()).orElse(null);
            var due = entry.getValue().stream().map(CreditInvoice::getDueDate).min(Comparator.naturalOrder()).orElse(null);
            keys.add(new Key(entry.getKey(), late != null, late, due));
        }
        keys.sort(Comparator.comparing((Key k) -> !k.late())
                .thenComparing(Key::oldestLate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Key::earliestDue, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Key::agreementId));
        return keys.stream().map(Key::agreementId).toList();
    }

    private CreditPayDtos.PayableInvoice toPayableInvoice(CreditInvoice invoice, BigDecimal openClaims, LocalDate today) {
        return new CreditPayDtos.PayableInvoice(invoice.getId(), invoice.getInvoiceNumber(), invoice.getSupplierOrderId(),
                invoice.getStatus(), invoice.getAmount(), invoice.getPaidAmount(), invoice.getCreditedAmount(),
                invoice.outstanding(), invoice.getDueDate(), invoice.getOverdueAfter(),
                com.costonomy.mp.credit.domain.CreditDueState.of(invoice.getStatus(), invoice.getDueDate(),
                        invoice.getOverdueAfter(), today),
                com.costonomy.mp.credit.domain.CreditDueState.daysToDue(invoice.getStatus(), invoice.getDueDate(), today),
                openClaims == null ? BigDecimal.ZERO : openClaims);
    }

    // ── The response ─────────────────────────────────────────────────────

    /** Builds the response from the rows; used by every endpoint and by tests. */
    public CreditPayDtos.AttemptResponse toResponse(CreditPaymentAttempt attempt, boolean includeKey, boolean reopened) {
        return toResponse(attempt, includeKey, reopened, null);
    }

    private CreditPayDtos.AttemptResponse toResponse(CreditPaymentAttempt attempt, boolean includeKey, boolean reopened,
                                                     BigDecimal walletBalanceAfter) {
        LocalDate today = invoiceService.today();
        var rows = attemptInvoices.findByAttemptIdOrderByCreditInvoiceIdAsc(attempt.getId());
        var invoiceById = invoices.findAllById(rows.stream().map(CreditPaymentAttemptInvoice::getCreditInvoiceId).toList())
                .stream().collect(Collectors.toMap(CreditInvoice::getId, i -> i));
        var byAgreement = new HashMap<Long, List<CreditInvoice>>();
        for (var row : rows) {
            var invoice = invoiceById.get(row.getCreditInvoiceId());
            if (invoice != null) {
                byAgreement.computeIfAbsent(row.getCreditAgreementId(), k -> new ArrayList<>()).add(invoice);
            }
        }
        var order = orderAgreements(byAgreement, today);
        var rowsByAgreement = rows.stream().collect(Collectors.groupingBy(CreditPaymentAttemptInvoice::getCreditAgreementId));

        var suppliers = new ArrayList<CreditPayDtos.AttemptSupplier>();
        var invoiceRows = new ArrayList<CreditPayDtos.AttemptInvoice>();
        for (Long agreementId : order) {
            var group = rowsByAgreement.get(agreementId);
            Long storeId = group.get(0).getSupplierStoreId();
            var store = directory.store(storeId);
            String supplierName = store == null ? null : store.supplierName();
            BigDecimal amount = group.stream().map(CreditPaymentAttemptInvoice::getExpectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            var paidRows = group.stream().filter(r -> r.getOutcome() == CreditPayInvoiceOutcome.PAID).toList();
            BigDecimal paid = paidRows.stream().map(CreditPaymentAttemptInvoice::getExpectedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            Long repaymentId = paidRows.isEmpty() ? null : paidRows.get(0).getCreditRepaymentId();
            CreditDtos.RepaymentAgreementState state = null;
            if (!paidRows.isEmpty()) {
                var dues = invoiceService.duesFor(agreementId);
                var agreement = agreements.findById(agreementId).orElse(null);
                state = new CreditDtos.RepaymentAgreementState(dues.due(), dues.overdue(),
                        exposure.read(agreementId).available(), agreement == null ? null : agreement.getStatus());
            }
            suppliers.add(new CreditPayDtos.AttemptSupplier(agreementId, storeId, supplierName, group.size(),
                    amount, paid, repaymentId, state));
            group.stream().sorted(Comparator.comparing(CreditPaymentAttemptInvoice::getCreditInvoiceId)).forEach(r -> {
                var invoice = invoiceById.get(r.getCreditInvoiceId());
                invoiceRows.add(new CreditPayDtos.AttemptInvoice(r.getCreditInvoiceId(),
                        invoice == null ? null : invoice.getInvoiceNumber(), agreementId, storeId, supplierName,
                        r.getExpectedAmount(), r.getOutcome(), r.getSkipReason()));
            });
        }

        var refundRows = refunds.findByAttemptIdOrderByIdAsc(attempt.getId());
        BigDecimal refundAmount = refundRows.stream().map(r -> r.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String refundState = "NONE";
        if (!refundRows.isEmpty()) {
            boolean pending = refundRows.stream().anyMatch(r -> r.getStatus() == CreditPayRefundStatus.PENDING);
            boolean stuck = refundRows.stream().anyMatch(r -> r.getStatus() == CreditPayRefundStatus.PENDING
                    && r.getAttempts() >= MAX_REFUND_ATTEMPTS);
            refundState = stuck ? "NEEDS_ATTENTION" : pending ? "PENDING" : "COMPLETED";
        }
        boolean open = attempt.getChannel() == CreditPayChannel.ONLINE
                && attempt.getStatus() == CreditPaymentAttemptStatus.CREATED;
        String paidWith = attempt.getChannel() == CreditPayChannel.WALLET
                ? "WALLET" : attempt.getRazorpayPaymentId() == null ? null : CreditPaySettlement.mapMethod(attempt.getPaymentMethod());
        return new CreditPayDtos.AttemptResponse(attempt.getId(), attempt.getOutletId(), attempt.getStatus(),
                attempt.getMethod(), paidWith, attempt.getPaymentDetail(), attempt.getInvoiceCount(),
                attempt.getAmount(), attempt.getFeeAmount(), attempt.getTotalCharged(), attempt.getCurrency(),
                attempt.getRazorpayOrderId(), attempt.getRazorpayPaymentId(),
                includeKey && open ? provider.createAuthorizationPublicKey() : null, reopened,
                refundAmount, refundState, attempt.getCreatedAt(), attempt.getPaidAt(),
                open && attempt.getCreatedAt() != null ? attempt.getCreatedAt().plus(expireAfter) : null,
                walletBalanceAfter, suppliers, invoiceRows);
    }

    // ── Small things ─────────────────────────────────────────────────────

    private void requireOnline() {
        if (!onlineEnabled) {
            // Before anything is read or an idempotency key claimed: a refusal here leaves no trace.
            throw new BusinessException(ErrorCode.FORBIDDEN, "Paying credit online isn't available yet.");
        }
    }

    private boolean walletMethodOn() {
        return onlineEnabled && walletEnabled;
    }

    /** Another outlet's is "not found", like every restaurant-side credit endpoint. */
    private void requireOutlet(Long actorId, Long outletId) {
        accessControl.requireScoped(actorId, Permissions.CREDIT_REPAY, ScopeType.OUTLET, outletId, "Outlet");
    }

    private CreditPaymentAttempt attemptOf(Long outletId, Long attemptId) {
        return attempts.findById(attemptId).filter(a -> a.getOutletId().equals(outletId))
                .orElseThrow(() -> new NotFoundException("CreditPaymentAttempt", attemptId));
    }

    private static String truncate(String text) {
        return text == null || text.length() <= 500 ? text : text.substring(0, 500);
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
