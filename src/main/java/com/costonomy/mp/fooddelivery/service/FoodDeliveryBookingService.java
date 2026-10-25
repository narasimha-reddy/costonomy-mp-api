package com.costonomy.mp.fooddelivery.service;

import com.costonomy.mp.common.error.BusinessException;
import com.costonomy.mp.common.error.ErrorCode;
import com.costonomy.mp.common.error.NotFoundException;
import com.costonomy.mp.delivery.domain.VehicleType;
import com.costonomy.mp.delivery.provider.DeliveryProvider;
import com.costonomy.mp.delivery.provider.DeliveryProviderException;
import com.costonomy.mp.delivery.service.DeliveryDirectory;
import com.costonomy.mp.delivery.service.DeliveryProviderRegistry;
import com.costonomy.mp.delivery.service.DeliveryRecoveryService;
import com.costonomy.mp.delivery.service.ProviderReliabilityService;
import com.costonomy.mp.fooddelivery.domain.FoodDelivery;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryAttempt;
import com.costonomy.mp.fooddelivery.domain.FoodDeliveryStatus;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryAttemptRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryQuoteRepository;
import com.costonomy.mp.fooddelivery.repository.FoodDeliveryRepository;
import com.costonomy.mp.fooddelivery.web.dto.FoodDeliveryDtos;
import com.costonomy.mp.payment.provider.PaymentProvider;
import com.costonomy.mp.payment.provider.PaymentProviderException;
import com.costonomy.mp.payment.service.UnusablePaymentRefunds;
import com.costonomy.mp.wallet.service.WalletService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Creates a food delivery, holds its fee, books a courier with fallback, and ends it cleanly when nobody will take it
 * (D-197, doc 06 §16). Reading courier events, the restaurant API, the public tracking link and customer notifications
 * are separate; the seams to them are {@link #rebook}, {@link #settleFee} and {@link #cancel}.
 *
 * <p><b>The fee.</b> The restaurant agrees a maximum. That amount is held from its wallet before any courier is asked, and
 * only couriers quoting at or below it are. The whole hold stays until pickup, so a dearer replacement courier never needs a
 * second debit; {@link #settleFee} then returns the unused part. Cancelling before pickup, or running out of couriers,
 * returns all of it. Every refund is idempotent on its wallet reference.
 *
 * <p><b>Booking is a loop that talks to couriers outside any transaction</b>, in short transactions around it: reserve
 * an attempt, call the courier, record the answer. A slow courier does not hold a database connection, and an attempt that
 * was reserved and never answered (the process died) stops blocking after {@code pendingStaleAfter}.
 */
@Service
@Slf4j
public class FoodDeliveryBookingService {

    /** Statuses in which a new courier may be asked, provided no consignment is live. */
    private static final Set<FoodDeliveryStatus> BOOKABLE = Set.of(
            FoodDeliveryStatus.QUOTED, FoodDeliveryStatus.PROVIDER_UNAVAILABLE, FoodDeliveryStatus.PROVIDER_SELECTED);

    private final FoodDeliveryRepository deliveries;
    private final FoodDeliveryAttemptRepository attempts;
    private final FoodDeliveryQuoteRepository quotes;
    private final FoodDeliveryQuotingService quoting;
    private final FoodCourierSelector selector;
    private final DeliveryProviderRegistry registry;
    private final DeliveryDirectory directory;
    private final WalletService wallet;
    private final FoodDeliveryTimeline timeline;
    private final FoodDeliveryProperties properties;
    private final ProviderReliabilityService reliability;
    private final DeliveryRecoveryService recoveryService;
    private final PaymentProvider paymentProvider;
    private final UnusablePaymentRefunds gatewayRefunds;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public FoodDeliveryBookingService(FoodDeliveryRepository deliveries, FoodDeliveryAttemptRepository attempts,
                                      FoodDeliveryQuoteRepository quotes, FoodDeliveryQuotingService quoting,
                                      FoodCourierSelector selector, DeliveryProviderRegistry registry,
                                      DeliveryDirectory directory, WalletService wallet, FoodDeliveryTimeline timeline,
                                      FoodDeliveryProperties properties, ProviderReliabilityService reliability,
                                      DeliveryRecoveryService recoveryService,
                                      PaymentProvider paymentProvider,
                                      UnusablePaymentRefunds gatewayRefunds,
                                      JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.deliveries = deliveries;
        this.attempts = attempts;
        this.quotes = quotes;
        this.quoting = quoting;
        this.selector = selector;
        this.registry = registry;
        this.directory = directory;
        this.wallet = wallet;
        this.timeline = timeline;
        this.properties = properties;
        this.reliability = reliability;
        this.recoveryService = recoveryService;
        this.paymentProvider = paymentProvider;
        this.gatewayRefunds = gatewayRefunds;
        this.jdbc = jdbc;
        // Every step is its own transaction: a courier booked is a fact outside our database, so it is committed on its
        // own and never rolled back with something that fails later.
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * What the restaurant's system sends to ask for a delivery.
     *
     * @param maxFee the most it will pay; null means "up to 1.5 x the cheapest courier", rounded up to a rupee
     */
    public record CreateRequest(
            Long restaurantId, Long outletId, String idempotencyKey, String externalOrderRef,
            String customerName, String customerPhone,
            String dropAddress, String dropLandmark, BigDecimal dropLatitude, BigDecimal dropLongitude,
            String dropCity, String dropState, String dropPincode, String dropInstructions,
            BigDecimal orderValue, BigDecimal weightKg, Integer maxDeliveryMinutes, BigDecimal maxFee,
            String paymentMethod) {

        public CreateRequest(Long restaurantId, Long outletId, String idempotencyKey, String externalOrderRef,
                             String customerName, String customerPhone,
                             String dropAddress, String dropLandmark, BigDecimal dropLatitude, BigDecimal dropLongitude,
                             String dropCity, String dropState, String dropPincode, String dropInstructions,
                             BigDecimal orderValue, BigDecimal weightKg, Integer maxDeliveryMinutes, BigDecimal maxFee) {
            this(restaurantId, outletId, idempotencyKey, externalOrderRef, customerName, customerPhone,
                    dropAddress, dropLandmark, dropLatitude, dropLongitude, dropCity, dropState, dropPincode, dropInstructions,
                    orderValue, weightKg, maxDeliveryMinutes, maxFee, "WALLET");
        }
    }

    // ── Create ───────────────────────────────────────────────────────────

    /**
     * Create the delivery, hold the fee and book a courier. Safe to repeat: the same {@code idempotencyKey} from the same
     * restaurant returns the first delivery and books nothing more.
     *
     * @return the delivery as it stands: {@code PROVIDER_SELECTED} when a courier took it, {@code PROVIDER_UNAVAILABLE}
     *         when none did yet (the retry window then applies)
     * @throws BusinessException if the request is invalid, no courier covers the address or quotes within the agreed
     *                           maximum, or the wallet cannot hold the fee; in each case nothing is debited
     */
    public FoodDelivery create(CreateRequest request) {
        var existing = deliveries.findByRestaurantIdAndIdempotencyKey(request.restaurantId(), request.idempotencyKey());
        if (existing.isPresent()) {
            var cur = existing.get();
            if (isGatewayPayment(cur.getPaymentMethod()) && cur.getProviderOrderId() == null && cur.getMaxFee() != null) {
                try {
                    var intent = paymentProvider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                            "food-del-" + cur.getId(),
                            cur.getMaxFee().setScale(2, RoundingMode.HALF_UP),
                            "INR",
                            "Food delivery " + (cur.getExternalOrderRef() != null ? cur.getExternalOrderRef() : cur.getId()),
                            "food-del-" + cur.getId(),
                            true));
                    final String provOrderId = intent.providerOrderId();
                    tx.executeWithoutResult(status -> {
                        var row = deliveries.findById(cur.getId()).orElseThrow();
                        row.setProviderOrderId(provOrderId);
                        deliveries.save(row);
                    });
                    cur.setProviderOrderId(provOrderId);
                } catch (RuntimeException ex) {
                    log.warn("Failed to open payment gateway order on replayed food delivery {}: {}", cur.getId(), ex.getMessage());
                }
            }
            return cur;
        }
        validate(request);

        Long id = null;
        try {
            // Phase 1: Short transaction to insert delivery in REQUESTED status
            FoodDelivery created = tx.execute(status -> createRequested(request));
            id = created.getId();

            // Phase 2: External quote gathering outside any database transaction or row lock
            var outcome = quoting.gather(created);

            // Phase 3: Short transaction to validate quotes, hold fee from wallet, and advance to QUOTED
            final Long deliveryId = id;
            tx.executeWithoutResult(status -> holdAndAdvance(deliveryId, request, outcome));
        } catch (DataIntegrityViolationException duplicate) {
            // A simultaneous request with the same key won the unique index; hand back its delivery.
            return deliveries.findByRestaurantIdAndIdempotencyKey(request.restaurantId(), request.idempotencyKey())
                    .orElseThrow(() -> duplicate);
        } catch (RuntimeException ex) {
            if (id != null) {
                final Long deliveryId = id;
                try {
                    tx.executeWithoutResult(status -> cleanUpFailedCreate(deliveryId));
                } catch (Exception cleanEx) {
                    log.warn("Failed to clean up delivery {} after creation failure: {}", deliveryId, cleanEx.getMessage());
                }
            }
            throw ex;
        }

        FoodDelivery cur = deliveries.findById(id).orElseThrow();
        if (isGatewayPayment(cur.getPaymentMethod())) {
            try {
                var intent = paymentProvider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                        "food-del-" + cur.getId(),
                        cur.getMaxFee().setScale(2, RoundingMode.HALF_UP),
                        "INR",
                        "Food delivery " + (cur.getExternalOrderRef() != null ? cur.getExternalOrderRef() : cur.getId()),
                        "food-del-" + cur.getId(),
                        true));
                final String provOrderId = intent.providerOrderId();
                tx.executeWithoutResult(status -> {
                    var row = deliveries.findById(cur.getId()).orElseThrow();
                    row.setProviderOrderId(provOrderId);
                    deliveries.save(row);
                });
                cur.setProviderOrderId(provOrderId);
            } catch (RuntimeException ex) {
                log.warn("Failed to open payment gateway order for food delivery {}: {}", cur.getId(), ex.getMessage());
            }
            return cur;
        }

        book(id, "BOOKING");
        return deliveries.findById(id).orElseThrow();
    }

    private FoodDelivery createRequested(CreateRequest r) {
        Long ownerOfOutlet = jdbc.query("select restaurant_id from outlet where id = ?",
                (rs, row) -> rs.getLong(1), r.outletId()).stream().findFirst().orElse(null);
        // Not found, not forbidden: whether another restaurant's outlet exists is not for this caller to learn.
        if (ownerOfOutlet == null || !ownerOfOutlet.equals(r.restaurantId())) {
            throw new NotFoundException("Outlet", r.outletId());
        }
        var pickup = directory.dropFor(r.outletId());
        var pickupLocality = directory.dropLocality(r.outletId());
        if (pickup == null || pickup.latitude() == null || pickup.longitude() == null
                || isBlank(pickup.contactPhone()) || pickupLocality == null) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "This outlet needs a location and a contact phone before it can send deliveries.");
        }

        Instant now = Instant.now();
        var d = new FoodDelivery();
        d.setRestaurantId(r.restaurantId());
        d.setOutletId(r.outletId());
        d.setIdempotencyKey(r.idempotencyKey());
        d.setExternalOrderRef(r.externalOrderRef());
        d.setPickupAddress(pickup.address());
        d.setPickupLatitude(pickup.latitude());
        d.setPickupLongitude(pickup.longitude());
        d.setPickupContactName(pickup.contactName() == null ? pickup.name() : pickup.contactName());
        d.setPickupContactPhone(pickup.contactPhone());
        d.setCustomerName(r.customerName().trim());
        d.setCustomerPhone(r.customerPhone().trim());
        d.setDropAddress(r.dropAddress().trim());
        d.setDropLandmark(r.dropLandmark());
        d.setDropLatitude(r.dropLatitude());
        d.setDropLongitude(r.dropLongitude());
        d.setDropCity(r.dropCity().trim());
        d.setDropState(r.dropState().trim());
        d.setDropPincode(r.dropPincode().trim());
        d.setDropInstructions(r.dropInstructions());
        d.setOrderValue(r.orderValue());
        d.setWeightKg(r.weightKg());
        d.setVehicleType(VehicleType.fromWeight(r.weightKg(), false));
        d.setMaxDeliveryMinutes(r.maxDeliveryMinutes());
        String pm = r.paymentMethod() != null && !isBlank(r.paymentMethod())
                ? r.paymentMethod().toUpperCase() : "WALLET";
        d.setPaymentMethod(pm);
        d.setRequestedAt(now);
        d.setRoundStartedAt(now);
        d = deliveries.saveAndFlush(d);
        timeline.record(d, FoodDeliveryStatus.REQUESTED, "Delivery requested");
        return d;
    }

    private void holdAndAdvance(Long id, CreateRequest r, FoodDeliveryQuotingService.Outcome outcome) {
        var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
        Instant now = Instant.now();

        var priced = outcome.all().stream()
                .filter(q -> "QUOTED".equals(q.getStatus()) && q.getAmount() != null && q.getEtaMinutes() != null)
                .map(q -> q.getAmount()).toList();
        if (priced.isEmpty()) {
            boolean outsideRadius = outcome.all().stream().anyMatch(q ->
                    FoodDeliveryQuotingService.RADIUS_LIMIT_PROVIDER_CODE.equals(q.getProviderCode())
                    || ("UNSERVICEABLE".equals(q.getStatus()) && q.getFailureReason() != null
                        && q.getFailureReason().toLowerCase().contains("radius")));
            if (outsideRadius) {
                throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                        "Delivery destination is outside service radius (max 30.0 km).");
            }
            if (registry.enabledForFood().isEmpty()) {
                throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                        "No delivery partner supports this delivery in this area.");
            }
            if (!outcome.all().isEmpty() && outcome.all().stream().allMatch(q -> "FAILED".equals(q.getStatus()))) {
                throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                        "All delivery partners failed to provide a quote.");
            }
            if (!outcome.all().isEmpty() && outcome.all().stream().allMatch(q -> "UNSERVICEABLE".equals(q.getStatus()))) {
                throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                        "All delivery partners declined service for this route.");
            }
            throw new BusinessException(ErrorCode.DELIVERY_UNAVAILABLE,
                    "No delivery partner can take this delivery right now.");
        }
        BigDecimal cheapest = priced.stream().min(BigDecimal::compareTo).orElseThrow();
        BigDecimal maxFee = r.maxFee() != null ? r.maxFee()
                : cheapest.multiply(BigDecimal.valueOf(factor())).setScale(0, RoundingMode.CEILING);
        if (cheapest.compareTo(maxFee) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "The cheapest delivery partner costs ₹%s, above your limit of ₹%s."
                            .formatted(cheapest.stripTrailingZeros().toPlainString(), maxFee.stripTrailingZeros().toPlainString()));
        }

        d.setMaxFee(maxFee);
        if (isGatewayPayment(d.getPaymentMethod())) {
            d.setFee(maxFee);
            deliveries.save(d);
            timeline.record(d, FoodDeliveryStatus.REQUESTED, "Delivery fee quoted at ₹%s; waiting for payment via %s"
                    .formatted(maxFee.toPlainString(), paymentProvider.name()));
        } else {
            // Held before any courier is asked. If the wallet is short this throws and the whole step rolls back.
            wallet.debitFoodDelivery(d.getOutletId(), d.getId(), maxFee);
            d.setFeeDebitRef("food-delivery-" + d.getId());
            d.setFeeDebitedAt(now);
            d.moveTo(FoodDeliveryStatus.QUOTED);
            deliveries.save(d);
            timeline.record(d, FoodDeliveryStatus.QUOTED, "Delivery fee held; finding a delivery partner");
        }
    }

    private void cleanUpFailedCreate(Long deliveryId) {
        jdbc.update("delete from food_delivery_attempt where food_delivery_id = ?", deliveryId);
        jdbc.update("delete from food_delivery_event where food_delivery_id = ?", deliveryId);
        jdbc.update("delete from food_delivery_quote where food_delivery_id = ?", deliveryId);
        jdbc.update("delete from food_delivery_location where food_delivery_id = ?", deliveryId);
        jdbc.update("delete from food_delivery where id = ?", deliveryId);
    }

    /** The same bound the ranking uses: dearer than this many times the cheapest is not worth paying for reliability. */
    private double factor() {
        return reliability.settings().getCostFactor();
    }

    // ── Booking loop ─────────────────────────────────────────────────────

    /** What reserving the next attempt produced. */
    private record Reserved(Long attemptId, String providerCode, DeliveryProvider.BookingRequest request, BigDecimal quotedAmount) {
    }

    private enum Reservation { RESERVED, NONE, BUSY, NOT_BOOKABLE }

    private record Step(Reservation kind, Reserved reserved) {
        static Step of(Reservation kind) {
            return new Step(kind, null);
        }
    }

    private enum Result { BOOKED, FAILED, STAND_DOWN_NEEDED }

    /**
     * Ask couriers in the selector's order until one takes it. Each is its own short transaction around the courier call.
     * Ends with the delivery {@code PROVIDER_SELECTED}, or {@code PROVIDER_UNAVAILABLE} when none would.
     */
    void book(Long id, String attemptType) {
        while (true) {
            Step step = tx.execute(status -> reserveNext(id, attemptType));
            if (step.kind() == Reservation.BUSY || step.kind() == Reservation.NOT_BOOKABLE) {
                return;
            }
            if (step.kind() == Reservation.NONE) {
                tx.executeWithoutResult(status -> markUnavailable(id));
                return;
            }
            var reserved = step.reserved();
            DeliveryProvider.Booking booking = null;
            DeliveryProviderException failure = null;
            long startedNs = System.nanoTime();
            var adapter = registry.adapter(reserved.providerCode());
            try {
                booking = adapter.book(reserved.request());
            } catch (DeliveryProviderException ex) {
                failure = ex;
            } catch (Exception ex) {
                failure = new DeliveryProviderException(reserved.providerCode(), ex.getMessage(), true);
            }
            int callMs = (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNs) / 1_000_000L);

            boolean ambiguous = false;
            if (failure != null && ("TIMEOUT".equals(failure.errorClass()) || failure.retryable())) {
                if (adapter != null && adapter.supportsLookupByReference()) {
                    try {
                        var lookedUp = adapter.findByReference(reserved.request().sourceRef(), reserved.request().idempotencyKey());
                        if (lookedUp.isPresent()) {
                            var d = lookedUp.get();
                            booking = new DeliveryProvider.Booking(d.providerDeliveryId(), reserved.quotedAmount(), "INR", d.etaMinutes(), d.estimatedArrivalAt(), null);
                            failure = null;
                        }
                    } catch (Exception lookupEx) {
                        log.warn("Reference lookup failed for {}: {}", reserved.providerCode(), lookupEx.getMessage());
                        ambiguous = true;
                    }
                } else {
                    ambiguous = true;
                }
            }

            if (ambiguous) {
                final DeliveryProviderException finalFailure = failure;
                tx.executeWithoutResult(status -> recordAmbiguousOutcome(id, reserved, finalFailure, callMs));
                return;
            }

            var answer = booking;
            var error = failure;
            Result result = tx.execute(status -> recordAnswer(id, reserved, answer, error, callMs));
            if (result == Result.BOOKED) {
                return;
            }
            if (result == Result.STAND_DOWN_NEEDED && answer != null) {
                // The delivery was cancelled, or the courier's price broke the agreed maximum, while it was being booked.
                // The courier exists at the provider and nowhere we are looking: stand it down.
                cancelAtProvider(reserved.providerCode(), answer.providerDeliveryId(), "Booking not wanted");
                if (!tx.execute(status -> stillBookable(id))) {
                    return;
                }
            }
        }
    }

    private void recordAmbiguousOutcome(Long id, Reserved reserved, DeliveryProviderException failure, int callMs) {
        var d = deliveries.lockById(id).orElseThrow();
        var attempt = attempts.findById(reserved.attemptId()).orElseThrow();
        attempt.setCallMs(callMs);
        attempt.setOutcome("FAILED");
        attempt.setFailureCode("AMBIGUOUS_PROVIDER_OUTCOME");
        attempt.setFailureReason(failure != null ? failure.getMessage() : "Provider call timed out or failed ambiguously");
        attempt.setEndedCause("AMBIGUOUS_PROVIDER_OUTCOME");
        attempt.setEndedAt(Instant.now());
        attempts.save(attempt);

        d.setReconciliationRequired(true);
        d.setReconciliationReason("Booking outcome ambiguous with " + reserved.providerCode() + "; operator review required.");
        d.setFailureCode("AMBIGUOUS_PROVIDER_OUTCOME");
        d.setFailureReason("Booking outcome ambiguous with " + reserved.providerCode() + "; operator review required.");
        deliveries.save(d);
        timeline.record(d, "ReconciliationRequired", d.getStatus(), "Ambiguous outcome with " + reserved.providerCode() + "; manual reconciliation required.");
    }

    private boolean stillBookable(Long id) {
        var d = deliveries.findById(id).orElse(null);
        return d != null && BOOKABLE.contains(d.getStatus()) && d.getProviderDeliveryId() == null;
    }

    private Step reserveNext(Long id, String attemptType) {
        var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
        if (!BOOKABLE.contains(d.getStatus()) || d.getProviderDeliveryId() != null) {
            return Step.of(Reservation.NOT_BOOKABLE);
        }
        Instant now = Instant.now();

        // Someone else is mid-call. A reservation that never got an answer (the process died) stops blocking.
        for (var pending : attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(id)) {
            if (!"PENDING".equals(pending.getOutcome())) {
                continue;
            }
            if (pending.getStartedAt().isAfter(now.minus(properties.getPendingStaleAfter()))) {
                return Step.of(Reservation.BUSY);
            }
            pending.setOutcome("FAILED");
            pending.setFailureCode("ABANDONED");
            pending.setEndedCause("ABANDONED");
            pending.setEndedAt(now);
            attempts.save(pending);
        }

        Set<String> triedThisRound = attempts.findByFoodDeliveryIdOrderByAttemptNumberAsc(id).stream()
                .filter(a -> d.getRoundStartedAt() == null || !a.getStartedAt().isBefore(d.getRoundStartedAt()))
                .map(FoodDeliveryAttempt::getProviderCode).collect(Collectors.toSet());
        var choices = selector.order(d, d.getMaxFee(), now, triedThisRound);
        if (choices.isEmpty()) {
            return Step.of(Reservation.NONE);
        }
        var choice = choices.get(0);
        var quote = choice.quote();

        int number = d.getAttemptCount() + 1;
        var attempt = new FoodDeliveryAttempt();
        attempt.setFoodDeliveryId(id);
        attempt.setDeliveryProviderId(quote.getDeliveryProviderId());
        attempt.setProviderCode(quote.getProviderCode());
        attempt.setAttemptNumber(number);
        attempt.setAttemptType(attemptType);
        attempt.setOutcome("PENDING");
        attempt.setQuotedAmount(quote.getAmount());
        attempt.setStartedAt(now);
        attempt.setRankPosition(choice.rank());
        attempt.setSelectionReason(choice.reason());
        attempt.setReliabilityScore(choice.score());
        attempt.setBreakerState(choice.breakerState());
        attempt.setDecision(choice.decisionJson());
        attempt = attempts.save(attempt);
        d.setAttemptCount(number);
        deliveries.save(d);

        var request = new DeliveryProvider.BookingRequest(
                null, quote.getProviderQuoteId(),
                d.getPickupAddress(), d.getPickupLatitude(), d.getPickupLongitude(),
                d.getPickupContactName(), d.getPickupContactPhone(),
                d.getDropAddress(), d.getDropLatitude(), d.getDropLongitude(),
                d.getCustomerName(), d.getCustomerPhone(),
                // Ours, so a retried booking cannot put two couriers at one door.
                "mp-food-delivery-%d-%d".formatted(id, number),
                d.getWeightKg(), null,
                quote.getVehicleType() != null ? quote.getVehicleType() : d.getVehicleType(),
                directory.dropLocality(d.getOutletId()),
                new DeliveryProvider.Locality(d.getDropCity(), d.getDropState(), d.getDropPincode()),
                d.getOrderValue(), "FD-" + id);
        return new Step(Reservation.RESERVED, new Reserved(attempt.getId(), quote.getProviderCode(), request, quote.getAmount()));
    }

    private Result recordAnswer(Long id, Reserved reserved, DeliveryProvider.Booking booking,
                                DeliveryProviderException failure, int callMs) {
        var d = deliveries.lockById(id).orElseThrow();
        var attempt = attempts.findById(reserved.attemptId()).orElseThrow();
        attempt.setCallMs(callMs);
        Instant now = Instant.now();

        if (failure != null) {
            attempt.setOutcome("FAILED");
            attempt.setFailureCode("BOOKING_FAILED");
            attempt.setFailureReason(failure.getMessage());
            attempt.setRetryable(failure.retryable());
            attempt.setEndedCause("TIMEOUT".equals(failure.errorClass()) ? "PROVIDER_TIMEOUT" : "PROVIDER_REFUSED");
            attempt.setEndedAt(now);
            attempts.save(attempt);
            return Result.FAILED;
        }

        var quote = quotes.findByFoodDeliveryIdOrderByIdAsc(id).stream()
                .filter(q -> reserved.providerCode().equals(q.getProviderCode()) && "QUOTED".equals(q.getStatus()))
                .reduce((first, second) -> second).orElse(null);
        BigDecimal amount = booking.amount() != null ? booking.amount()
                : quote == null ? null : quote.getAmount();

        // Cancelled while the courier was being asked, or the courier's own price is above what the restaurant agreed.
        boolean stillWanted = BOOKABLE.contains(d.getStatus()) && d.getProviderDeliveryId() == null;
        boolean withinFee = amount != null && d.getMaxFee() != null && amount.compareTo(d.getMaxFee()) <= 0;
        if (!stillWanted || !withinFee) {
            attempt.setOutcome(stillWanted ? "FAILED" : "CANCELLED");
            attempt.setProviderDeliveryId(booking.providerDeliveryId());
            attempt.setFailureCode(stillWanted ? "PRICE_ABOVE_LIMIT" : "NOT_WANTED");
            attempt.setFailureReason(stillWanted
                    ? "The courier's price is above the agreed maximum" : "The delivery was cancelled while booking");
            attempt.setEndedCause(stillWanted ? "PRICE_ABOVE_LIMIT" : "CUSTOMER_CANCEL");
            attempt.setEndedAt(now);
            attempts.save(attempt);
            return Result.STAND_DOWN_NEEDED;
        }

        attempt.setOutcome("BOOKED");
        attempt.setProviderDeliveryId(booking.providerDeliveryId());
        attempts.save(attempt);

        var eta = booking.etaMinutes() != null ? booking.etaMinutes() : quote == null ? null : quote.getEtaMinutes();
        d.setDeliveryProviderId(attempt.getDeliveryProviderId());
        d.setProviderCode(reserved.providerCode());
        d.setProviderDeliveryId(booking.providerDeliveryId());
        d.setFee(amount);
        d.setCurrency(booking.currency() != null ? booking.currency() : "INR");
        d.setEtaMinutes(eta);
        d.setEstimatedArrivalAt(booking.estimatedArrivalAt() != null ? booking.estimatedArrivalAt()
                : eta == null ? null : now.plusSeconds(eta * 60L));
        if (booking.trackingUrl() != null) {
            d.setTrackingUrl(booking.trackingUrl());
        }
        d.setBookedAt(now);
        d.setAssignmentDeadline(now.plus(properties.getAssignmentTimeout()));
        d.setFailureCode(null);
        d.setFailureReason(null);
        d.moveTo(FoodDeliveryStatus.PROVIDER_SELECTED);
        deliveries.save(d);
        if (quote != null) {
            quote.setSelected(true);
            quotes.save(quote);
        }
        timeline.record(d, FoodDeliveryStatus.PROVIDER_SELECTED, "Finding a driver");
        return Result.BOOKED;
    }

    private void markUnavailable(Long id) {
        var d = deliveries.lockById(id).orElseThrow();
        if (d.getProviderDeliveryId() != null || !BOOKABLE.contains(d.getStatus())) {
            return;
        }
        if (d.getStatus() != FoodDeliveryStatus.PROVIDER_UNAVAILABLE) {
            d.moveTo(FoodDeliveryStatus.PROVIDER_UNAVAILABLE);
        }
        d.setFailureCode("ALL_PROVIDERS_FAILED");
        d.setFailureReason("No delivery partner could take this delivery.");
        deliveries.save(d);
        timeline.record(d, FoodDeliveryStatus.PROVIDER_UNAVAILABLE, "No delivery partner could take this delivery yet");
    }

    // ── Retry window ─────────────────────────────────────────────────────

    /**
     * One pass over a delivery nobody has taken. Within the retry window it asks again from scratch (fresh quotes, every
     * courier eligible once more); past it, the delivery ends {@code FAILED} with code NO_COURIER and the whole fee is
     * returned. Called by the retry job; checks again under the lock, so a cancel or a rebook that got there first wins.
     */
    public void retry(Long id) {
        // Phase 1: Reserve under short lock
        FoodDelivery snapshot = tx.execute(status -> {
            var d = deliveries.lockById(id).orElse(null);
            if (d == null || (d.getStatus() != FoodDeliveryStatus.PROVIDER_UNAVAILABLE
                    && d.getStatus() != FoodDeliveryStatus.QUOTE_FAILED)) {
                return null;
            }
            Instant now = Instant.now();
            Instant baseTime = d.getPaidAt() != null ? d.getPaidAt() : d.getRequestedAt();
            if (!now.isBefore(baseTime.plus(properties.getRetryWindow()))) {
                failNoCourier(d);
                return null;
            }
            d.setRoundStartedAt(now);
            deliveries.save(d);
            return d;
        });

        if (snapshot == null) {
            return;
        }

        // Phase 2: External quote gathering outside any database transaction or row lock
        var outcome = quoting.gather(snapshot);

        // Phase 3: Persist quote gathering result under short lock
        Boolean proceed = tx.execute(status -> {
            var d = deliveries.lockById(id).orElse(null);
            if (d == null || (d.getStatus() != FoodDeliveryStatus.PROVIDER_UNAVAILABLE
                    && d.getStatus() != FoodDeliveryStatus.QUOTE_FAILED)) {
                return false;
            }
            if (!outcome.anyServiceable()) {
                deliveries.save(d);
                return false;
            }
            d.moveTo(FoodDeliveryStatus.QUOTED);
            deliveries.save(d);
            return true;
        });

        if (Boolean.TRUE.equals(proceed)) {
            book(id, "RETRY");
        }
    }

    private void failNoCourier(FoodDelivery d) {
        d.moveTo(FoodDeliveryStatus.FAILED);
        d.setFailureCode("NO_COURIER");
        d.setFailureReason("No delivery partner could take this delivery in time.");
        d.setTrackingExpiresAt(Instant.now().plus(Duration.ofHours(24)));
        refundAll(d, "Food delivery could not be arranged");
        deliveries.save(d);
        timeline.record(d, FoodDeliveryStatus.FAILED, "No delivery partner could take this delivery in time; fee returned");
    }

    // ── A courier let go, or took too long ───────────────────────────────

    private record RebookReleaseInfo(String providerCode, String providerDeliveryId) {}

    /**
     * Take the delivery from a courier that let go before pickup (a rider cancelled, a pickup failed, or no rider came in
     * time) and ask the next one. The old consignment is cancelled at the provider so two couriers cannot arrive. Does
     * nothing if the delivery has moved on: it checks the consignment it was told about is still the current one.
     * Keeps provider cancellation outside database row locks.
     *
     * @param failedProviderDeliveryId the consignment that let go, or null to take whatever is current
     * @param cause                    why, as an attempt end cause (ASSIGNMENT_TIMEOUT, RIDER_CANCELLED, ...)
     * @return true if another courier was booked
     */
    public boolean rebook(Long id, String failedProviderDeliveryId, String cause, String reason) {
        // Phase 1: Under short lock, verify state and update attempt/delivery
        RebookReleaseInfo info = tx.execute(status -> {
            var d = deliveries.lockById(id).orElse(null);
            if (d == null || d.getProviderDeliveryId() == null || !d.getStatus().isCancellable()
                    || d.getStatus() == FoodDeliveryStatus.QUOTED || d.getStatus() == FoodDeliveryStatus.PROVIDER_UNAVAILABLE) {
                return null;
            }
            if (failedProviderDeliveryId != null && !failedProviderDeliveryId.equals(d.getProviderDeliveryId())) {
                return null;
            }
            String provCode = d.getProviderCode();
            String provDeliveryId = d.getProviderDeliveryId();

            attempts.findFirstByFoodDeliveryIdOrderByAttemptNumberDesc(d.getId()).ifPresent(a -> {
                if ("BOOKED".equals(a.getOutcome())) {
                    a.setOutcome("CANCELLED");
                    a.setFailureReason(reason);
                    a.setEndedAt(Instant.now());
                    if (a.getEndedCause() == null) {
                        a.setEndedCause(cause);
                    }
                    attempts.save(a);
                }
            });
            d.setProviderDeliveryId(null);
            d.setDriverName(null);
            d.setDriverPhone(null);
            d.setDriverVehicle(null);
            d.setAssignmentDeadline(null);

            d.moveTo(FoodDeliveryStatus.PROVIDER_SELECTED);
            deliveries.save(d);
            timeline.record(d, "FoodDeliveryReassigned", FoodDeliveryStatus.PROVIDER_SELECTED, "Finding another driver");
            return new RebookReleaseInfo(provCode, provDeliveryId);
        });

        if (info == null) {
            return false;
        }

        // Phase 2: External provider cancellation outside database row lock
        if (info.providerDeliveryId() != null) {
            boolean cancelFailed = cancelAtProvider(info.providerCode(), info.providerDeliveryId(), reason == null ? "Released" : reason);
            if (cancelFailed) {
                recoveryService.scheduleCancellation("FOOD", id, info.providerCode(), info.providerDeliveryId(), reason);
            }
        }

        // Phase 3: Book next courier
        book(id, "REASSIGNMENT");
        return deliveries.findById(id).map(d -> d.getProviderDeliveryId() != null).orElse(false);
    }

    /** Hands a booking that never got a rider to the next courier. Called by the sweep. */
    public boolean rebookOnTimeout(Long id) {
        Boolean overdue = tx.execute(status -> deliveries.findById(id)
                .map(d -> d.getStatus() == FoodDeliveryStatus.PROVIDER_SELECTED && d.getAssignmentDeadline() != null
                        && !d.getAssignmentDeadline().isAfter(Instant.now().minus(properties.getSweepGrace())))
                .orElse(false));
        return Boolean.TRUE.equals(overdue) && rebook(id, null, "ASSIGNMENT_TIMEOUT", "No driver was assigned in time");
    }

    // ── Money ────────────────────────────────────────────────────────────

    private record SettleHoldInfo(Long outletId, BigDecimal unused) {}

    /**
     * Return the unused part of the hold once the courier is fixed (the food has been picked up): the agreed maximum less
     * what the courier actually costs. Idempotent. Called by whatever ingests the courier's pickup event.
     * Keeps wallet network call outside database transactions and locks.
     *
     * @return the amount returned now; zero if there was nothing to return or it had already been
     */
    public BigDecimal settleFee(Long id) {
        // Phase 1: Under short lock, verify status, fee, and amount to refund
        SettleHoldInfo info = tx.execute(status -> {
            var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
            if (!(d.getStatus().isInFlight() || d.getStatus() == FoodDeliveryStatus.DELIVERED) || d.getFee() == null) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "The fee is settled once the food has been picked up.");
            }
            BigDecimal unused = d.netHeld().subtract(d.getFee());
            if (unused.signum() <= 0) {
                return new SettleHoldInfo(d.getOutletId(), BigDecimal.ZERO);
            }
            if (d.getFeeRefundRef() != null && d.getFeeRefundRef().endsWith("-adjust")) {
                return new SettleHoldInfo(d.getOutletId(), BigDecimal.ZERO);
            }
            return new SettleHoldInfo(d.getOutletId(), unused);
        });

        if (info == null || info.unused().signum() <= 0) {
            return BigDecimal.ZERO;
        }

        // Phase 2: External wallet call outside database transaction and row lock
        boolean refunded = false;
        try {
            refunded = wallet.refundFoodDelivery(info.outletId(), id, "adjust", info.unused(),
                    "Food delivery fee · unused part returned");
        } catch (Exception ex) {
            log.warn("Wallet adjust failed for food delivery {}: {}", id, ex.getMessage());
        }

        // Phase 3: Persist refund outcome under short lock
        final boolean refundSuccess = refunded;
        return tx.execute(status -> {
            var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
            if (refundSuccess) {
                d.setFeeRefundedAmount(d.getFeeRefundedAmount().add(info.unused()));
                d.setFeeRefundedAt(Instant.now());
                d.setFeeRefundRef("food-delivery-" + d.getId() + "-adjust");
                deliveries.save(d);
                return info.unused();
            } else {
                recoveryService.scheduleRefund(d.getId(), d.getOutletId(), "adjust", info.unused(),
                        "Food delivery fee · unused part returned");
                return BigDecimal.ZERO;
            }
        });
    }

    private record CancelHoldInfo(Long deliveryId, Long outletId, String providerCode, String providerDeliveryId, BigDecimal held) {}

    /**
     * Cancel before pickup: the courier is stood down and the whole hold returned. After pickup the food is on the road
     * and nobody may cancel it. Keeps provider and wallet network calls outside database transactions and locks.
     */
    public FoodDelivery cancel(Long id, String reason) {
        // Phase 1: Under short lock, verify state and update delivery status
        CancelHoldInfo info = tx.execute(status -> {
            var d = deliveries.lockById(id).orElseThrow(() -> new NotFoundException("FoodDelivery", id));
            if (d.getStatus() == FoodDeliveryStatus.CANCELLED) {
                return null;
            }
            if (!d.getStatus().isCancellable()) {
                throw new BusinessException(ErrorCode.INVALID_STATE_TRANSITION,
                        "This delivery can't be cancelled once the food has been picked up.");
            }
            String provCode = d.getProviderCode();
            String provDeliveryId = d.getProviderDeliveryId();
            BigDecimal held = d.netHeld();

            attempts.findFirstByFoodDeliveryIdOrderByAttemptNumberDesc(d.getId()).ifPresent(a -> {
                if ("BOOKED".equals(a.getOutcome())) {
                    a.setOutcome("CANCELLED");
                    a.setFailureReason(reason);
                    a.setEndedAt(Instant.now());
                    if (a.getEndedCause() == null) {
                        a.setEndedCause("CUSTOMER_CANCEL");
                    }
                    attempts.save(a);
                }
            });
            d.setProviderDeliveryId(null);
            d.setDriverName(null);
            d.setDriverPhone(null);
            d.setDriverVehicle(null);
            d.setAssignmentDeadline(null);

            Instant now = Instant.now();
            d.moveTo(FoodDeliveryStatus.CANCELLED);
            d.setCancelledAt(now);
            d.setFailureReason(reason);
            d.setTrackingExpiresAt(now.plus(Duration.ofHours(24)));
            d.setFee(BigDecimal.ZERO);
            deliveries.save(d);
            timeline.record(d, FoodDeliveryStatus.CANCELLED, reason == null ? "Cancelled" : reason);

            return new CancelHoldInfo(d.getId(), d.getOutletId(), provCode, provDeliveryId, held);
        });

        if (info == null) {
            return deliveries.findById(id).orElseThrow();
        }

        // Phase 2: External provider cancellation outside database row lock
        if (info.providerDeliveryId() != null) {
            boolean cancelFailed = cancelAtProvider(info.providerCode(), info.providerDeliveryId(), reason == null ? "Released" : reason);
            if (cancelFailed) {
                recoveryService.scheduleCancellation("FOOD", info.deliveryId(), info.providerCode(), info.providerDeliveryId(), reason);
            }
        }

        // Phase 3: External wallet refund outside database transaction and row lock
        if (info.held().signum() > 0) {
            boolean ok = false;
            try {
                ok = wallet.refundFoodDelivery(info.outletId(), info.deliveryId(), "refund", info.held(), "Food delivery cancelled");
            } catch (Exception ex) {
                log.warn("Wallet refund threw for food delivery {}: {}", info.deliveryId(), ex.getMessage());
            }
            final boolean refundSuccess = ok;
            tx.executeWithoutResult(status -> {
                var d = deliveries.lockById(info.deliveryId()).orElse(null);
                if (d != null) {
                    if (refundSuccess) {
                        d.setFeeRefundedAmount(d.getFeeRefundedAmount().add(info.held()));
                        d.setFeeRefundedAt(Instant.now());
                        d.setFeeRefundRef("food-delivery-" + d.getId() + "-refund");
                        deliveries.save(d);
                    } else {
                        recoveryService.scheduleRefund(d.getId(), d.getOutletId(), "refund", info.held(), "Food delivery cancelled");
                    }
                }
            });
        }

        return deliveries.findById(id).orElseThrow();
    }

    /** Returns everything still held. Idempotent on the wallet reference, so a repeat pays nothing twice. */
    private void refundAll(FoodDelivery d, String reason) {
        BigDecimal held = d.netHeld();
        if (held.signum() > 0) {
            boolean ok = false;
            try {
                ok = wallet.refundFoodDelivery(d.getOutletId(), d.getId(), "refund", held, reason);
            } catch (Exception ex) {
                log.warn("Wallet refund threw for food delivery {}: {}", d.getId(), ex.getMessage());
            }
            if (ok) {
                d.setFeeRefundedAmount(d.getFeeRefundedAmount().add(held));
                d.setFeeRefundedAt(Instant.now());
                d.setFeeRefundRef("food-delivery-" + d.getId() + "-refund");
            } else {
                recoveryService.scheduleRefund(d.getId(), d.getOutletId(), "refund", held, reason);
            }
        }
        d.setFee(BigDecimal.ZERO);
    }

    // ── Provider side ────────────────────────────────────────────────────

    private boolean cancelAtProvider(String providerCode, String providerDeliveryId, String reason) {
        var adapter = providerCode == null ? null : registry.adapter(providerCode);
        if (adapter == null || providerDeliveryId == null) {
            return false;
        }
        try {
            adapter.cancel(providerDeliveryId, reason);
            return false;
        } catch (DeliveryProviderException ex) {
            log.warn("Could not cancel {} consignment {}: {}", providerCode, providerDeliveryId, ex.getMessage());
            return true;
        }
    }

    private record StalePendingInfo(
            Long deliveryId,
            Long attemptId,
            String providerCode,
            int attemptNumber,
            BigDecimal quotedAmount,
            Long deliveryProviderId,
            String ref
    ) {}

    public void recoverStalePendingAttempt(Long attemptId) {
        // Phase 1: Verify eligibility under short lock
        StalePendingInfo info = tx.execute(status -> {
            var attemptOpt = attempts.findById(attemptId);
            if (attemptOpt.isEmpty() || !"PENDING".equals(attemptOpt.get().getOutcome())) {
                return null;
            }
            var curAttempt = attemptOpt.get();
            var d = deliveries.lockById(curAttempt.getFoodDeliveryId()).orElse(null);
            if (d == null) return null;

            Instant now = Instant.now();
            if (!curAttempt.getStartedAt().isBefore(now.minus(properties.getPendingStaleAfter()))) {
                return null;
            }

            String ref = "mp-food-delivery-%d-%d".formatted(d.getId(), curAttempt.getAttemptNumber());
            return new StalePendingInfo(d.getId(), curAttempt.getId(), curAttempt.getProviderCode(),
                    curAttempt.getAttemptNumber(), curAttempt.getQuotedAmount(), curAttempt.getDeliveryProviderId(), ref);
        });

        if (info == null) {
            return;
        }

        // Phase 2: External provider lookup outside database transaction and row lock
        var adapter = registry.adapter(info.providerCode());
        Optional<DeliveryProvider.ProviderDelivery> lookedUp = Optional.empty();
        boolean lookupSupported = adapter != null && adapter.supportsLookupByReference();
        boolean lookupThrew = false;

        if (lookupSupported) {
            try {
                lookedUp = adapter.findByReference(info.ref(), info.ref());
            } catch (Exception ex) {
                lookupThrew = true;
                log.warn("Error reconciling stale attempt {} with {}: {}", info.attemptId(), info.providerCode(), ex.getMessage());
            }
        }

        final Optional<DeliveryProvider.ProviderDelivery> finalLookedUp = lookedUp;
        final boolean finalLookupSupported = lookupSupported;
        final boolean finalLookupThrew = lookupThrew;

        // Phase 3: Persist outcome under short lock
        tx.executeWithoutResult(status -> {
            var d = deliveries.lockById(info.deliveryId()).orElse(null);
            if (d == null) return;
            var curAttempt = attempts.findById(info.attemptId()).orElse(null);
            if (curAttempt == null || !"PENDING".equals(curAttempt.getOutcome())) {
                return;
            }

            Instant now = Instant.now();
            if (finalLookupSupported && !finalLookupThrew) {
                if (finalLookedUp.isPresent()) {
                    var booking = finalLookedUp.get();
                    log.info("Recovered accepted booking from {} for food delivery {}", curAttempt.getProviderCode(), d.getId());
                    if (BOOKABLE.contains(d.getStatus()) && d.getProviderDeliveryId() == null) {
                        curAttempt.setOutcome("BOOKED");
                        curAttempt.setProviderDeliveryId(booking.providerDeliveryId());
                        curAttempt.setEndedAt(now);
                        attempts.save(curAttempt);

                        d.setDeliveryProviderId(curAttempt.getDeliveryProviderId());
                        d.setProviderCode(curAttempt.getProviderCode());
                        d.setProviderDeliveryId(booking.providerDeliveryId());
                        d.setFee(curAttempt.getQuotedAmount());
                        d.setCurrency("INR");
                        d.setEtaMinutes(booking.etaMinutes());
                        d.setEstimatedArrivalAt(booking.estimatedArrivalAt() != null ? booking.estimatedArrivalAt()
                                : booking.etaMinutes() == null ? null : now.plusSeconds(booking.etaMinutes() * 60L));
                        d.setBookedAt(now);
                        d.setAssignmentDeadline(now.plus(properties.getAssignmentTimeout()));
                        d.moveTo(FoodDeliveryStatus.PROVIDER_SELECTED);
                        deliveries.save(d);
                        timeline.record(d, FoodDeliveryStatus.PROVIDER_SELECTED, "Booking recovered from " + curAttempt.getProviderCode());
                        return;
                    } else {
                        curAttempt.setOutcome("CANCELLED");
                        curAttempt.setEndedCause("RECOVERED_BUT_NOT_WANTED");
                        curAttempt.setEndedAt(now);
                        attempts.save(curAttempt);
                        return;
                    }
                } else {
                    log.info("Provider {} confirmed pending attempt {} not found", curAttempt.getProviderCode(), curAttempt.getId());
                    curAttempt.setOutcome("FAILED");
                    curAttempt.setFailureCode("ABANDONED");
                    curAttempt.setEndedCause("ABANDONED_STALE_PENDING");
                    curAttempt.setEndedAt(now);
                    attempts.save(curAttempt);
                    timeline.record(d, "StaleAttemptAbandoned", d.getStatus(), "Stale booking attempt abandoned as provider confirmed not booked");
                    return;
                }
            }

            curAttempt.setOutcome("FAILED");
            curAttempt.setFailureCode("AMBIGUOUS_STALE_PENDING");
            curAttempt.setEndedCause("AMBIGUOUS_STALE_PENDING");
            curAttempt.setEndedAt(now);
            attempts.save(curAttempt);

            d.setReconciliationRequired(true);
            d.setReconciliationReason("Stale attempt %d with %s could not be reconciled".formatted(curAttempt.getAttemptNumber(), curAttempt.getProviderCode()));
            deliveries.save(d);
            timeline.record(d, "ReconciliationRequired", d.getStatus(), "Stale attempt could not be reconciled with " + curAttempt.getProviderCode());
        });

        // If booking was recovered but not wanted, stand down at provider outside transaction
        if (finalLookupSupported && !finalLookupThrew && finalLookedUp.isPresent()) {
            var d = deliveries.findById(info.deliveryId()).orElse(null);
            if (d != null && (d.getProviderDeliveryId() == null || !d.getProviderDeliveryId().equals(finalLookedUp.get().providerDeliveryId()))) {
                cancelAtProvider(info.providerCode(), finalLookedUp.get().providerDeliveryId(), "Delivery no longer active");
            }
        }
    }

    // ── Validation ───────────────────────────────────────────────────────

    private static void validate(CreateRequest r) {
        require(r.restaurantId() != null && r.outletId() != null, "A restaurant and an outlet are required.");
        require(!isBlank(r.idempotencyKey()), "An idempotency key is required.");
        require(!isBlank(r.customerName()), "The customer's name is required.");
        require(r.customerPhone() != null && r.customerPhone().replaceAll("\\D", "").length() >= 10,
                "The customer's phone number must have at least 10 digits.");
        require(!isBlank(r.dropAddress()), "The delivery address is required.");
        require(r.dropLatitude() != null && r.dropLongitude() != null
                        && r.dropLatitude().abs().compareTo(BigDecimal.valueOf(90)) <= 0
                        && r.dropLongitude().abs().compareTo(BigDecimal.valueOf(180)) <= 0,
                "The delivery location (latitude and longitude) is required.");
        require(!isBlank(r.dropCity()) && !isBlank(r.dropState()) && !isBlank(r.dropPincode()),
                "The delivery city, state and pincode are required.");
        require(r.orderValue() != null && r.orderValue().signum() > 0, "The order value is required.");
        require(r.weightKg() != null && r.weightKg().signum() > 0
                        && r.weightKg().compareTo(VehicleType.TWO_WHEELER.maxWeightKg()) <= 0,
                "The weight must be above zero and within what a bike can carry (%s kg).".formatted(VehicleType.TWO_WHEELER.maxWeightKg()));
        require(r.maxDeliveryMinutes() == null || r.maxDeliveryMinutes() > 0, "The delivery time limit must be above zero.");
        require(r.maxFee() == null || r.maxFee().signum() > 0, "The fee limit must be above zero.");
        if (r.paymentMethod() != null && !isBlank(r.paymentMethod())) {
            String pm = r.paymentMethod().toUpperCase();
            require(Set.of("WALLET", "GATEWAY", "PAYMENT_GATEWAY", "UPI", "ONLINE", "CARD").contains(pm),
                    "Payment method must be WALLET or GATEWAY.");
        }
    }

    public static boolean isGatewayPayment(String method) {
        return method != null && (
                method.equalsIgnoreCase("GATEWAY")
                || method.equalsIgnoreCase("PAYMENT_GATEWAY")
                || method.equalsIgnoreCase("UPI")
                || method.equalsIgnoreCase("ONLINE")
                || method.equalsIgnoreCase("CARD")
        );
    }

    /**
     * Start payment gateway checkout for a food delivery fee.
     */
    public FoodDeliveryDtos.InitiateFoodDeliveryPaymentResponse initiatePayment(Long outletId, Long deliveryId) {
        var d = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("FoodDelivery", deliveryId));
        if (!d.getOutletId().equals(outletId)) {
            throw new NotFoundException("FoodDelivery", deliveryId);
        }
        if (d.getStatus().isTerminal()) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "This delivery has ended.");
        }
        if (d.getPaidAt() != null || d.getFeeDebitedAt() != null || d.getStatus() != FoodDeliveryStatus.REQUESTED) {
            if (d.getProviderOrderId() != null && d.getPaidAt() == null) {
                return new FoodDeliveryDtos.InitiateFoodDeliveryPaymentResponse(
                        d.getId(), d.getProviderOrderId(), paymentProvider.createAuthorizationPublicKey(),
                        d.getMaxFee() != null ? d.getMaxFee().setScale(2, RoundingMode.HALF_UP) : BigDecimal.ZERO, "INR");
            }
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT,
                    "This delivery fee has already been paid or cannot be paid.");
        }
        if (d.getProviderOrderId() != null) {
            return new FoodDeliveryDtos.InitiateFoodDeliveryPaymentResponse(
                    d.getId(), d.getProviderOrderId(), paymentProvider.createAuthorizationPublicKey(),
                    d.getMaxFee().setScale(2, RoundingMode.HALF_UP), "INR");
        }

        BigDecimal fee = d.getMaxFee() != null ? d.getMaxFee() : d.getFee();
        if (fee == null || fee.signum() <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "No delivery fee available to pay.");
        }
        BigDecimal amount = fee.setScale(2, RoundingMode.HALF_UP);

        PaymentProvider.AuthorizationIntent intent;
        try {
            intent = paymentProvider.createAuthorization(new PaymentProvider.AuthorizationRequest(
                    "food-del-" + d.getId(), amount, "INR",
                    "Food delivery " + (d.getExternalOrderRef() != null ? d.getExternalOrderRef() : d.getId()),
                    "food-del-" + d.getId(), true));
        } catch (RuntimeException ex) {
            log.warn("Could not open checkout for food delivery {}: {}", deliveryId, ex.getMessage());
            throw new BusinessException(ErrorCode.PAYMENT_FAILED,
                    "We couldn't start checkout. Nothing was charged. Please try again.");
        }

        final String provOrderId = intent.providerOrderId();
        tx.executeWithoutResult(status -> {
            var row = deliveries.findById(deliveryId).orElseThrow();
            row.setProviderOrderId(provOrderId);
            row.setPaymentMethod("GATEWAY");
            deliveries.save(row);
        });

        return new FoodDeliveryDtos.InitiateFoodDeliveryPaymentResponse(
                d.getId(), intent.providerOrderId(), intent.publicKey(), amount, "INR");
    }

    /**
     * Confirm payment for a food delivery fee: verifies checkout signature and payment facts
     * with the payment provider, marks fee paid, and triggers courier booking.
     */
    public FoodDelivery confirmPayment(Long outletId, Long deliveryId,
                                       FoodDeliveryDtos.ConfirmFoodDeliveryPaymentRequest request) {
        if (request.paymentId() == null || request.paymentId().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Payment ID is required.");
        }
        if (request.signature() == null || request.signature().isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "Signature is required.");
        }
        var d = deliveries.findById(deliveryId)
                .orElseThrow(() -> new NotFoundException("FoodDelivery", deliveryId));
        if (!d.getOutletId().equals(outletId)) {
            throw new NotFoundException("FoodDelivery", deliveryId);
        }
        if (request.paymentId().equals(d.getPaymentId()) && d.getPaidAt() != null) {
            return d;
        }
        if (d.getProviderOrderId() == null) {
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, "Checkout was never opened for this food delivery.");
        }

        if (!paymentProvider.verifyCheckoutSignature(d.getProviderOrderId(), request.paymentId(), request.signature())) {
            log.warn("Food delivery {} confirm rejected: signature did not verify", deliveryId);
            throw new BusinessException(ErrorCode.VALIDATION_ERROR,
                    "We couldn't verify that payment. Nothing was charged.");
        }

        PaymentProvider.ProviderPayment payment;
        try {
            payment = paymentProvider.fetchPayment(request.paymentId());
        } catch (PaymentProviderException ex) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "We couldn't find that payment.");
        }
        if (payment.status() != PaymentProvider.ProviderPaymentStatus.CAPTURED) {
            throw new BusinessException(ErrorCode.PAYMENT_FAILED, "That payment didn't go through. Nothing was charged.");
        }
        // Not this checkout's payment: nothing of ours to refund, and a refund here would let anyone name any payment id.
        if (!d.getProviderOrderId().equals(payment.providerOrderId())) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, "That payment does not match this checkout order.");
        }

        // The payment is this checkout's and the money has been taken. From here it is either used or sent back.
        if (!settleGatewayPayment(deliveryId, payment, "client")) {
            boolean back = deliveries.findById(deliveryId).map(x -> x.getGatewayRefundedAt() != null).orElse(false);
            throw new BusinessException(ErrorCode.PAYMENT_STATE_CONFLICT, back
                    ? "This delivery can no longer take your payment, so it has been refunded to where it came from."
                    : "This delivery can no longer take your payment. We are refunding it and will finish shortly.");
        }
        return deliveries.findById(deliveryId).orElseThrow();
    }

    /**
     * Decide what a captured gateway payment of this checkout is for, and do it. One place for the client's confirm, the
     * provider's webhook and the sweep, so they cannot disagree.
     *
     * <p>Used when the delivery is waiting for it (and the amount is right): it is marked paid, the courier is booked.
     * Anything else (cancelled or expired before the restaurant paid, a second payment, the wrong amount) is refunded to
     * its source, and found again by the sweep if the refund could not be made now. Never leaves captured money stranded.
     *
     * @return true if the payment was used (or had already been); false if it was refunded or is queued to be
     */
    private boolean settleGatewayPayment(Long deliveryId, PaymentProvider.ProviderPayment payment, String via) {
        String paymentId = payment.providerPaymentId();
        var d = deliveries.findById(deliveryId).orElseThrow();
        if (paymentId.equals(d.getPaymentId()) && d.getPaidAt() != null) {
            return true;
        }
        if (d.getGatewayRefundedAt() != null) {
            return false;
        }

        BigDecimal expected = d.getMaxFee() == null ? null : d.getMaxFee().setScale(2, RoundingMode.HALF_UP);
        boolean amountRight = expected != null && payment.capturedAmount() != null
                && payment.capturedAmount().compareTo(expected) == 0;

        boolean usable = false;
        if (amountRight && d.getPaidAt() == null && d.getStatus() == FoodDeliveryStatus.REQUESTED) {
            Instant now = Instant.now();
            // Quotes may have lapsed while the restaurant was in checkout: asked again, outside the transaction.
            var existingQuotes = quotes.findByFoodDeliveryIdOrderByIdAsc(deliveryId);
            boolean allExpired = existingQuotes.isEmpty() || existingQuotes.stream()
                    .allMatch(q -> q.getExpiresAt() != null && q.getExpiresAt().isBefore(now));
            if (allExpired) {
                try {
                    quoting.gather(d);
                } catch (Exception ex) {
                    log.warn("Failed to re-gather quotes on {} confirm for food delivery {}: {}", via, deliveryId, ex.getMessage());
                }
            }
            usable = Boolean.TRUE.equals(tx.execute(status -> {
                var row = deliveries.lockById(deliveryId).orElseThrow();
                // Rechecked under the lock: a cancel or another payment may have got here first.
                if (row.getPaidAt() != null || row.getStatus() != FoodDeliveryStatus.REQUESTED) {
                    return false;
                }
                row.setPaymentId(paymentId);
                row.setPaymentMethod("GATEWAY");
                row.setPaidAt(now);
                row.setRoundStartedAt(now);
                row.setFeeDebitRef("food-delivery-" + row.getId() + "-gw-" + paymentId);
                row.setFeeDebitedAt(now);
                row.moveTo(FoodDeliveryStatus.QUOTED);
                deliveries.save(row);
                timeline.record(row, FoodDeliveryStatus.QUOTED, "Delivery fee paid via " + paymentProvider.name()
                        + ("client".equals(via) ? "" : " (" + via + ")") + "; finding a delivery partner");
                return true;
            }));
        }
        if (usable) {
            book(deliveryId, "BOOKING");
            return true;
        }

        // Cannot be used. Back to where it came from, outside any transaction; the sweep retries what fails.
        String refundId = gatewayRefunds.refundRest(paymentId, payment.capturedAmount(), "food-del-unusable-");
        if (refundId != null) {
            tx.executeWithoutResult(status -> {
                var row = deliveries.lockById(deliveryId).orElseThrow();
                if (row.getGatewayRefundedAt() == null) {
                    row.setGatewayRefundId(refundId);
                    row.setGatewayRefundedAt(Instant.now());
                    deliveries.save(row);
                    timeline.record(row, row.getStatus(), "A payment that arrived too late or twice was refunded to its source");
                }
            });
        }
        return false;
    }

    /**
     * Reconciles a food delivery fee payment captured at the gateway (webhook or sweep): uses it if the delivery is
     * waiting for it, otherwise refunds it. See {@link #settleGatewayPayment}.
     *
     * @return true if the event was dealt with (used, already used, or refunded or queued for refund)
     */
    public boolean reconcileWebhookPayment(String providerOrderId, String providerPaymentId) {
        if (providerOrderId == null && providerPaymentId == null) {
            return false;
        }
        FoodDelivery d = null;
        if (providerOrderId != null) {
            d = deliveries.findByProviderOrderId(providerOrderId).orElse(null);
        }
        if (d == null && providerPaymentId != null) {
            d = deliveries.findByPaymentId(providerPaymentId).orElse(null);
        }
        if (d == null || providerPaymentId == null) {
            return false;
        }
        if (d.getPaidAt() != null && providerPaymentId.equals(d.getPaymentId())) {
            return true;
        }
        if (d.getGatewayRefundedAt() != null) {
            return true;
        }

        PaymentProvider.ProviderPayment payment;
        try {
            payment = paymentProvider.fetchPayment(providerPaymentId);
        } catch (Exception ex) {
            log.warn("Could not fetch payment {} for food delivery {}: {}", providerPaymentId, d.getId(), ex.getMessage());
            return false;
        }
        if (payment.status() != PaymentProvider.ProviderPaymentStatus.CAPTURED) {
            log.warn("Payment {} for food delivery {} is {}, not CAPTURED", providerPaymentId, d.getId(), payment.status());
            return false;
        }
        // Only this checkout's own payments: a refund must never follow a payment id that belongs to something else.
        if (d.getProviderOrderId() == null || !d.getProviderOrderId().equals(payment.providerOrderId())) {
            return false;
        }
        settleGatewayPayment(d.getId(), payment, "webhook");
        return true;
    }

    /**
     * Sweeps food deliveries whose gateway checkout was opened and not settled: recovers payments whose confirm or
     * webhook was lost, refunds payments that landed on a delivery that had ended, and cancels a delivery the
     * restaurant never paid for within {@code paymentExpiry}.
     */
    public int sweepStaleGatewayPayments() {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(Duration.ofSeconds(30));
        Instant createdAfter = now.minus(Duration.ofDays(1));
        var stale = deliveries.findStaleGatewayDeliveries(staleBefore, createdAfter, 50);

        int handled = 0;
        for (var d : stale) {
            if (d.getProviderOrderId() == null) {
                continue;
            }
            try {
                var paymentOpt = paymentProvider.findPaymentForOrder(d.getProviderOrderId());
                if (paymentOpt.isPresent()
                        && paymentOpt.get().status() == PaymentProvider.ProviderPaymentStatus.CAPTURED) {
                    if (reconcileWebhookPayment(d.getProviderOrderId(), paymentOpt.get().providerPaymentId())) {
                        handled++;
                        log.info("Sweep settled captured food delivery payment: delivery={}, order={}, payment={}",
                                d.getId(), d.getProviderOrderId(), paymentOpt.get().providerPaymentId());
                    }
                    continue;
                }
                // Nothing was paid. A delivery still waiting past the expiry is cancelled, not left open for ever.
                if (d.getStatus() == FoodDeliveryStatus.REQUESTED
                        && d.getCreatedAt() != null
                        && d.getCreatedAt().isBefore(now.minus(properties.getPaymentExpiry()))) {
                    cancel(d.getId(), "Payment was not completed in time");
                    handled++;
                }
            } catch (Exception ex) {
                log.warn("Failed to sweep payment for food delivery {}: {}", d.getId(), ex.getMessage());
            }
        }
        return handled;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw new BusinessException(ErrorCode.VALIDATION_ERROR, message);
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
