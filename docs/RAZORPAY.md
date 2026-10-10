# Running against Razorpay's sandbox

Local development runs on the mock provider, and nothing here is needed for it.
This is for checking payments against Razorpay itself (D-098). Test mode never
moves real money.

## 1. Keys

Razorpay Dashboard, **Test Mode** on, then Settings → API Keys → Generate Test
Key. Put them in your gitignored `src/main/resources/application-local.properties`,
never in `application.properties` and never with a default value:

```properties
costonomy.mp.providers.payment=RAZORPAY
costonomy.mp.razorpay.key-id=rzp_test_...
costonomy.mp.razorpay.key-secret=...
costonomy.mp.razorpay.webhook-secret=...
```

The key id reaches the app through the payment intent — it is publishable by
design. The secret and the webhook secret never leave the adapter.

Switching the provider does not change the local database; payments made on the
mock stay `MOCK` and are confirmed against the mock, which is no longer running.
Test with fresh orders.

## 2. Webhooks

Razorpay cannot reach `localhost`, so tunnel it (`ngrok http 7070`) and register

```
https://<tunnel>/costonomy-mp-api/api/v1/webhooks/razorpay
```

under Settings → Webhooks, with your own secret (the same value as
`webhook-secret` above) and at least `payment.authorized`, `payment.captured` and
`payment.failed`. The handler does not trust the payload's figures: it verifies
the signature, deduplicates on the `X-Razorpay-Event-Id` header, and asks
Razorpay for the payment's current state.

Without webhooks the flow still completes — the app's confirm call and the
reconciliation sweep both ask Razorpay directly — so a missing tunnel costs
recovery paths, not the happy path.

## 3. The app

- **Web** (`npm run web`) loads Razorpay's checkout.js on first payment. Nothing
  to install.
- **iOS / Android** need a development build, because `react-native-razorpay` is
  native: `npx expo prebuild` then `npx expo run:ios` or `run:android`. Expo Go
  cannot open the payment window and says so.

## 4. Test instruments

From Razorpay's test card page (re-check it if it has been a while):

| What | Value |
|---|---|
| Visa, domestic | `4100 2800 0000 1007` |
| Mastercard, domestic | `5555 5100 0008 1006` |
| UPI success / failure | `success@razorpay` / `failure@razorpay` |

Any future expiry and any CVV. At the OTP step, four or more digits succeed and
fewer fail.

## 5. The automated suite

`costonomy-mp-mobile/tools/razorpay-e2e` pays real test-mode orders through the
web app and Razorpay's checkout — test cards, the demo bank's Success and
Failure, in-checkout OTP, netbanking — and covers the failure paths: declines,
retries within one order, a closed window, a lost confirm (recovered by the
sweep and by a webhook), forged and duplicate webhooks, and refund idempotency.

**Refunds (D-104).** A refund is credited to the outlet's wallet, with no call to
Razorpay. Razorpay sees a refund only when the restaurant withdraws: each part of
a withdrawal is a refund on the original `pay_…`, sent by the refund job with
`X-Refund-Idempotency: mandi-refund-{id}`, and followed while Razorpay reports it
pending. In the dashboard, a refunded order therefore shows no refund until the
money is withdrawn.
Each case checks our database and Razorpay's own record. Its README lists the
cases and how to run them.

What it does not cover: UPI (not offered on the test account's checkout yet), and
a webhook actually delivered by Razorpay through a tunnel — the suite sends
correctly signed requests in Razorpay's shape instead.

## 6. A cancelled order whose money was debited (D-109)

A card is a hold; UPI, netbanking, wallet apps and pay-later debit the payer at
authorisation, and Razorpay refunds nothing until it is captured. So cancelling
before "ready" depends on how the order was paid:

```
AUTHORIZED ──cancel, method = card──────────────► RELEASED (CARD_HOLD_DROPPED)   no Razorpay call
     │
     └──cancel, any other method or unknown──► CANCEL_PENDING
                                                   │  settleCancellations (every 15 s) asks Razorpay
                                                   ├─ authorised, card ─────────► RELEASED (CARD_HOLD_DROPPED)
                                                   ├─ authorised, not a card ───► capture (cancel-capture-{id}) ─► CAPTURED
                                                   │                                  + refund cancel-order-{orderId}, to the source
                                                   │                                  ─► refund job ─► FULLY_REFUNDED
                                                   ├─ Razorpay returned it itself ► RELEASED (PROVIDER_AUTO_REFUND)
                                                   └─ anything else ────────────► stays, review_required_at set, ERROR

CREATED ──cancel──► stays CREATED, cancel_requested_at set; money that arrives later ─► CANCEL_PENDING
FAILED (INTENT_EXPIRED) ──late money──► CANCEL_PENDING
```

Settings: `costonomy.mp.razorpay.manual-expiry-minutes` (default 4320, three days;
sets the order's expiry and how long a supplier may take to mark ready, less six
hours), `costonomy.mp.razorpay.cancel-refund-speed` (`normal`, or `optimum` for an
instant refund where Razorpay can, at a per-refund fee), and
`costonomy.mp.payments.cancel-interval`.

A payment is "funded" for its order only while it is held or taken **and** the order was not
cancelled (`Payment.fundsSecuredForOrder()`): money captured to send back funds nothing, so
neither the release of a draft nor "ready" can proceed on it.

**The hold is the payment's own.** `manual-expiry-minutes` is sent with each new order and stored
on that payment (`payment.hold_minutes`); the guard on "ready" and the alerts use the stored value.
Raising the setting affects only orders made after the change. Payments with no stored value use
the current setting.

**Only "Razorpay does not know that payment" sends a cancellation to a person.** Razorpay does
**not** answer an unknown id with 404: on `GET /v1/payments/{id}` it answers **HTTP 400**,
`error.code = BAD_REQUEST_ERROR`, `error.description = "The id provided does not exist"` (the
provider also maps a real 404). `RazorpayPaymentProvider` reports exactly that answer, and no other
400, as `NOT_FOUND`, and `CancellationService` then sets `review_required_at` (order shows
`RETURN_DELAYED`, ERROR "needs a person", the three-hourly re-check, `clear-review`). The match is
deliberately narrow: `BAD_REQUEST_ERROR` **and** a description that is only "the id/payment/order/
refund ... does not exist / not found". Every other 400 (an amount, "The requested URL was not
found on the server" from a mistyped path, another error code, a body that cannot be read) keeps
waiting with no state change, and its WARN is written once an hour per payment, not every run. A
429 (the run stops, WARN once per 15 minutes), 401/403 (ERROR "refused our credentials", once per
15 minutes, the run stops) or 5xx also leave the payment `CANCEL_PENDING` untouched for the next
run. A run that stops early (rate limit, refused keys, the time budget) still writes the reminders
about payments already waiting for a person; it only skips the calls to Razorpay. **Unverified in
test mode (V-7): the exact 400 body**; it is taken from the repository's own fixture. If Razorpay's
wording differs, the payment simply keeps waiting (safe) and the hourly "still CANCEL_PENDING" ERROR
is the signal.

**Refunds.** A refund answered 429 or 401/403 is retried every run and never goes to
`NEEDS_REVIEW`, and **those sends do not count toward the five-attempt limit**, so a rate limit
followed by one 5xx cannot strand a refund. A 429 or 401/403 stops the whole refund run (a WARN once
per 15 minutes; refused keys are an ERROR once per 15 minutes). The run reads refunds oldest
attempt first, so a refund that is refused on its own goes to the back and the others are sent on
the following runs. A refund that has been `FAILED` or still `REQUESTED` for more than an hour is
an ERROR line `Refund N of payment M has been FAILED|REQUESTED ...`, once an hour per refund; so is
one left `PROCESSING` with no provider refund id for more than an hour (the process died mid-send).
Those stuck refunds are in the same oldest-attempt-first list as the others, so one refused refund
cannot keep them from being resent.

**Capture at "ready".** A 429 or 401/403 on the capture leaves the payment `CAPTURE_PENDING`; the
capture run stops and the next one (ten seconds later) tries again, and it never marks the payment
`FAILED`. Refused keys are an ERROR once per 15 minutes (`refused our credentials capturing
payment N`), a 429 a WARN. A payment still `CAPTURE_PENDING` after its attempt and past the point
where a supplier would be refused at "ready" (the hold limit less its margin) is an ERROR once an
hour: `Payment N is still CAPTURE_PENDING and its hold ... lapses`. Act on it before the hold ends.
A capture is expected within seconds of "ready", so one that has kept failing for more than an hour
(timed from its first failed attempt, in `payment_transaction`) is an ERROR too, once an hour:
`Payment N has been CAPTURE_PENDING and failing to capture since ...`, long before the hold runs low.

**Runbook: a capture Razorpay refused for good.** Any other 4xx on the capture (a 400 because the
authorisation has lapsed, or on the amount) makes the payment `FAILED` while the order stays
`READY_FOR_PICKUP`, and nothing reads a `FAILED` payment again. It is one ERROR line:
`Payment N for order M: capture refused, supplier not paid, a person must act (provider CODE: ...)`.
What ops does: open the payment in the Razorpay dashboard and check whether it is still `authorized`
(then it can be captured there, and the row corrected by hand), already `captured`, or
`refunded`/expired (the buyer got the money back). Whether and how the supplier is then paid, when
the goods have already gone, is a business decision, not something the system does: raise it with
finance before touching the payment or order rows.

**A key or mode mix-up.** If Razorpay answers "the id does not exist" for the first two payments
a cancellation run asks about, the run stops with one ERROR (`provider does not know 2 payments in a
row: check API keys/mode/base URL`) and sends nothing to review. One
unknown payment on its own still goes to review.

**Runbook: a payment waiting for a person.** Find it by the ERROR line
`Cancellation of payment N needs a person: ...` (and the reminder
`Payment N has been waiting for a person since ...`, after three hours and then daily). The order
shows `RETURN_DELAYED`. The job asks Razorpay about it again every three hours and only acts when the
answer is final and matches the order (Razorpay returned it itself, or it is captured with nothing
refunded); otherwise it stays. To hand it back to the normal run once the cause is fixed or checked
in the Razorpay dashboard: `POST /api/v1/admin/payments/{id}/clear-review` with `{"reason": "what you
checked"}` as an operator with `PAYMENT_RECONCILE` (role `OPS_FINANCE`); it is audited as
`PAYMENT_REVIEW_CLEARED` and moves no money. A refund in `NEEDS_REVIEW` is a separate matter and is
decided by a person with `REFUND_OPERATE` (section 7).

**Fee.** `provider_fee` is Razorpay's `fee`, which already includes GST (`tax` is a part of it).

**Known limitations (older behaviour; 3 to 5 are closed by D-110, below).**
1. A `CREATED` payment past the one-day lookup window whose Razorpay order has only a returned
   attempt (refunded, now read as `RELEASED`) is not expired by the sweep, because the lookup finds
   something: it is asked about every sweep run for good and its order stays a draft.
2. An `AUTHORIZED` payment that Razorpay shows as `refunded` **with** `captured = true` (captured
   and refunded by hand in the dashboard) is ignored as an out-of-order event, so its order can still
   be marked ready and the capture then fails. An ops-only situation.
3. **A payment in review that someone refunded by hand in the Razorpay dashboard** used to have no
   exit through the API (fixed in D-110). Razorpay shows such a payment `refunded` with `captured = true`,
   which the cancellation re-check acts on neither way, so the row stays `CANCEL_PENDING` with a daily ERROR
   and the order shows `RETURN_DELAYED`. It now has an audited action:
   `POST /api/v1/admin/payments/{id}/returned-outside` with `{"providerRefundId": "rfnd_...", "note": "..."}`
   as an operator with `REFUND_OPERATE`. The provider is read, not believed: it must show the payment
   captured and refunded in full, and list that refund under the payment, processed, for the payment's whole
   amount (422 `REFUND_VERIFICATION_FAILED` otherwise); the payment must be a `CANCEL_PENDING` one waiting for
   a person (409 otherwise). Then, in one transaction, the payment becomes `FULLY_REFUNDED` (captured and
   refunded amounts set, review cleared), the cancellation refund is recorded as `COMPLETED` under its usual key
   `cancel-order-{orderId}` (so the job cannot raise a second) with the provider's refund id, and a
   `PAYMENT_RETURNED_OUTSIDE` audit row names the operator and the note. The hand-run SQL that used to be
   here is no longer needed.
4. **A refund sent again after an ambiguous answer is looked up first** (fixed in D-110). A refund whose send
   timed out, got a 5xx or was claimed by a process that died is never resent on a hunch: the job first lists
   the payment's refunds at Razorpay (`GET /v1/payments/{id}/refunds`) and adopts one whose `receipt`
   (`mandi-refund-{id}`) or `notes.mandi_refund_id` is ours, completing it or following it while it is pending.
   It only sends if none is there. If the list cannot be read the refund is not sent, and the attempt counts
   (or, for a 429 or refused keys, does not). A 404 from the list is "cannot be read" too: it is what another account's
   keys would say, and the earlier send may be at the real one. This no longer depends on how long Razorpay remembers
   `X-Refund-Idempotency` (still unverified, V-5).
5. **A refund in `NEEDS_REVIEW` had no exit but the database** (fixed in D-110). Operations now has
   `REFUND_OPERATE` actions: verify, retry, put a withdrawal part back in the wallet, send a cancellation
   refund to the wallet, mark completed (checked against Razorpay), and block or unblock a payment as a refund
   source. See section 7.

In the dashboard a cancelled UPI order shows a captured payment and a refund, not a
hold. To test by hand against the mock: pay with
`MockPaymentProvider.completeCheckout(orderId, "upi")`; the sandbox checkout does not
offer UPI on this account yet (section 5).

## 7. A withdrawal part Razorpay will not send (D-110)

A withdrawal takes the money out of the wallet at once and asks Razorpay to refund it to the card afterwards,
one refund per source payment. When Razorpay refuses a part the money had already left the wallet and gone
nowhere. D-110 puts it back, but only on proof that the payer was not refunded.

**What is guaranteed, exactly.** The system never re-credits a part automatically while Razorpay's own records show
that the payer was, or may have been, paid another way: the list of the payment's refunds holds a refund that is not
ours, or the payment's `amount_refunded` is more than our refunds explain, or either cannot be read, or the payment
is unknown to Razorpay. Then the part goes to a person, and is never re-credited by the system. This does **not** rest on
Razorpay's wording. It is not a guarantee about a refund Razorpay has not made yet (a list that lags, a `failed` refund
that later succeeds): that is what the late-success watch is for, and it can only tell someone, not take money back.

**Before the debit.** `WalletWithdrawalService.withdraw` asks Razorpay (`GET /v1/payments/{id}`) about each
source, oldest credit first (the largest first only when the oldest ten cannot cover the request at all), until enough is covered, ten sources or twenty seconds: a payment Razorpay does not
know, one that is not captured there, or one already fully refunded is **blocked**
(`payment.provider_refund_blocked_at`/`_reason`: `PAYMENT_UNKNOWN`, `NOT_CAPTURED`, `REFUNDED_ELSEWHERE`) and its
wallet money stays spendable; one that cannot be read, or is older than
`costonomy.mp.razorpay.refund-window-days` (180, unverified, V-6), is skipped for now and not blocked; the rest
are capped at what Razorpay says is left less every refund of ours still in flight, and any that changed since the
read (`RefundRepository.unconfirmedOn`, over-estimating on purpose). The amount a refusal offers is computed with the
same figure, so asking for exactly what was offered is accepted.
**Wrong keys or another account's mode:** every source asked about is read, whatever the ones before it said. If Razorpay
answers normally about any of them, the keys are the right ones and every source it did not know is a payment that is
gone, and is blocked (so an outlet whose two oldest sources are dead still withdraws from the ones behind them). Only
if it did not know two or more different sources and answered about **none** is it a configuration fault, not dead
payments: nothing is blocked, nothing is debited (422, "could not be checked"), and one throttled ERROR says so. One
unknown source alone, with no other answered, is a payment that is gone and is blocked. An unreachable source (a
timeout, a 429, refused keys) is not an answer. Asking for more than can go back is
**422 `WITHDRAWAL_EXCEEDS_REFUNDABLE`** with `details` `requested`, `withdrawableNow`, `blocked`, `unavailable` and
`reason` (`SOURCE_BLOCKED`, `PROVIDER_UNREACHABLE` or `NO_REFUND_MONEY`), and `checkedSources` / `uncheckedSources` (how many
sources were asked about; when some were not, the message says more may be withdrawable in a further step, and so does
every such count on a successful withdrawal response); the app offers "Withdraw ₹{withdrawableNow}
instead" as a new request with a new key. Nothing is debited. The check is advisory: Razorpay can change its mind
before the send, and everything below is what makes that safe.

**After a refusal** (`RazorpayPaymentProvider.refundFailureKind`, only a label; the money decision is always the
read of Razorpay's refund list):

| Razorpay says | kind | What happens |
|---|---|---|
| timeout, I/O error, 5xx, a 200 with no refund id, an idempotency conflict | `AMBIGUOUS` | retried with the same key, listed before each resend; after five attempts to review. Never put back automatically |
| 429 | `THROTTLED` | retried, not counted |
| 401 / 403 | `CONFIG` | retried, not counted, ERROR, pauses new withdrawals; never put back |
| 408, 409 | `AMBIGUOUS` | as above: the provider may have acted |
| 404, or 400 "does not exist" | `PAYMENT_UNKNOWN` | `REJECTED`, then read. A list or payment read that itself answers 404 is not proof (see below) and goes to review. If both reads **succeed**, Razorpay plainly knows the payment and the refusal was wrong (a keys or mode mix-up, since fixed): the part is **sent again** (`REJECTED` to `REQUESTED`, audited `REFUND_REQUEUED`, with the look at the list that precedes every resend), not put back and its source not blocked; after five attempts it goes to review (`CONTRADICTED_REFUSAL`) |
| 400 "fully refunded" / "already refunded" | `ALREADY_REFUNDED` | `REJECTED`, then the same proof as every other refusal; a refund of ours is adopted; anything else on the payment goes to review, source blocked `REFUNDED_ELSEWHERE` if it is refunded in full. **The reads must also show it**: `amount_refunded` at the payment's full amount, all of it explained by refunds of ours; a refusal the reads do not corroborate (Razorpay's write said fully refunded, its list and payment say not) goes to review (`CONTRADICTED_REFUSAL`), never back to the wallet |
| 400 "greater than" / "exceeds" / "refundable" | `OVER_REFUND` | the same proof. With nothing on the payment but refunds of ours (an over-estimated allowance) it is put back; with a refund that is not ours it goes to review. Tested before the balance row, so "exceeds the refundable balance" is never read as our account's balance |
| 400 "status should be captured" | `NOT_CAPTURED` | verified, put back, source blocked |
| 400 refund window | `WINDOW_PASSED` | verified, put back, source blocked |
| 400 "balance" / "insufficient" | `INSUFFICIENT_BALANCE` | verified, put back, **not** blocked, ERROR, pauses new withdrawals |
| any other 4xx | `REJECTED_OTHER` | the same proof; a second put back for the same payment blocks it (`OPS`) |
| refund made and then reported `failed` | `PROVIDER_FAILED` | asked again after an hour; still failed: `REJECTED`, verified, put back, not blocked |

**Verified** is all of this, read at least two minutes after the first send (`costonomy.mp.refunds.reversal-min-age`,
the list may lag), whatever the refusal said:
1. `GET /v1/payments/{id}/refunds` shows none of ours (matched by `receipt` or `notes.mandi_refund_id`; a refund
   Razorpay reports `failed` is not money that left). One of ours found is adopted, never put back.
2. The same list shows no other non-failed refund on the payment: nothing without our `mandi-refund-` receipt that
   is not a refund we recorded. Someone else's refund, however small, sends the part to review (and blocks the source
   `REFUNDED_ELSEWHERE` if the payment is refunded in full).
3. `GET /v1/payments/{id}` shows `amount_refunded` no larger than our own listed refunds explain.
4. Both reads succeeded, and the payment read carries `amount_refunded` and `amount` (a body without them is unreadable,
   never "nothing was refunded"). Unreadable: the refusal stays `REJECTED` and is tried again. A payment (or list)
   Razorpay answers 404 for is not proof either: one such refund is sent to review; two different payments and no
   other answered is a configuration fault (below).

Then, in one transaction with
the wallet locked first, then the refund row, then the payment: the refund becomes `REVERSED` (one conditional
status change), the wallet is credited `WITHDRAWAL_REVERSAL` with the unique reference
`withdrawal-reversal-{refundId}`, and the row is audited (`REFUND_REVERSED`) and published
(`WithdrawalReversed`). It is an ERROR line: money promised out did not go out. A cancellation refund is never
credited to a wallet by the system; it goes to review. Every refund `REJECTED` for more than thirty minutes is an ERROR line
`Refund N REJECTED for M min`, whatever its place in the queue or what the run made of it.

**Why it went to review.** A part the system sends to a person carries `refund.review_cause`, because the provider's
own refusal no longer says why: `REFUNDED_ANOTHER_WAY` (the payer was, or may have been, refunded outside Mandi;
`review_ref` is that refund's id when the list shows it), `CONTRADICTED_REFUSAL`, `PAYMENT_GONE` (Razorpay does not know
the payment), `NOT_A_WITHDRAWAL`. The queue shows both. **A part with `REFUNDED_ANOTHER_WAY` is never put back in a wallet
by anyone** (`recredit` and `to-wallet` answer 422 `REFUND_VERIFICATION_FAILED`): the restaurant would be paid twice. The
exits are `mark-completed` against that refund (checked against Razorpay), `retry` (which clears the cause; Razorpay
then refuses what it cannot do), and, when that refund is **not this part's**, the two-person record below. `verify` and `recredit` run the same proof as the system (another refund on the list, or
more refunded than ours explain): the result is `FOREIGN_REFUND`, not `NONE_OF_OURS`, and a `recredit` or `to-wallet` whose own
read shows one is refused.

**When the foreign refund is not this part's.** A part debited from the wallet, refused, and held because of an unrelated
refund (a goodwill refund in the dashboard, or a refund for another purpose) would otherwise have no exit: not re-credited,
`mark-completed` needs the same amount, and a retry lands back in review. Four ways out, all read against Razorpay and audited:
- **`foreign-refund-not-this-part`** (two people, always, whatever the amount). A note, `evidence`, and `providerRefundIds`
  (up to six). It needs a `verify` from the last ten minutes that found a refund that is not ours, the minimum age since the
  last send for an `AMBIGUOUS` part, and the second person names the same refund ids (the first approval is bound to them; a
  read of the refund, a retry, a status change or 24 hours voids it). When the second person acts the payment's refunds are
  read again: each id must be listed on the payment, not failed, and not ours, and (below) worth less than the part. It records a judgement and moves no money:
  `review_cause = FOREIGN_NOT_THIS_PART`, `review_ref` = the ids. The proof then leaves out **exactly those ids and their
  amounts, for this refund only**; any other refund on the list, another id of the same amount, or an `amount_refunded`
  those do not explain is still `FOREIGN_REFUND`, and a second refund needs its own two people. Then `verify` (fresh,
  showing none of ours) and `recredit` with all its usual gates and **always two different people with evidence, whatever the amount** (evidence and the minimum age
  for an ambiguous part, its own read). **Only a refund that cannot be the part's own money can be recorded:** every refund named,
  with those already recorded on this part, must be worth **strictly less than the part**, and all of them together **strictly less
  than the part** (422 `REFUND_VERIFICATION_FAILED` at the first approval, the second and under the lock; nothing recorded). A refund worth
  the part or more looks like compensation for exactly this part (a payer refunded 400 by hand for a refused part of 400), and putting
  the part back as well would pay it twice: its exit is the plain `mark-completed` when it is the part's amount exactly, or
  `mark-completed` with `confirmPayerRefundedInFull` when the payment is refunded in full (or `returned-outside`). The rule is applied
  again whenever the proof is read: a record that does not hold is no record and the refund is foreign again. A recorded refund that has since **become ours** (another part was closed against it) counts as ours and is
  removed from the record before it is judged or summed, so one refund is never counted both as excluded and as ours; a recorded refund that
  has since **failed** at the provider counts as nothing (it neither voids the record of the others nor adds to the sum). The late-success watch
  honours the same record; a `retry` clears it.
- **`mark-completed` with `confirmPayerRefundedInFull: true`**, for a refund that covers this part and more (a dashboard
  refund of the whole payment for a smaller part, or one refund for two parts). The provider must show the payment refunded in
  full, the refund must be listed, processed and not ours, and the parts closed against it never add up to more than it. The
  part is `COMPLETED` with `provider_refund_id` left empty (`review_cause = COMPLETED_BY_OTHER_REFUND`, `review_ref` = that refund;
  the source is blocked `REFUNDED_ELSEWHERE`); the wallet is not credited, the payer has the money. Above ₹10,000 a second person
  makes the same call.
- **`mark-completed` with `confirmRefundCoversThisPart: true`**, for a refund made by hand that **covers this part although
  its amount differs and the payment is not refunded in full** (payment 1000, part 400 refused, support refunded 500 by hand: the 400
  and 100 goodwill). Same checks as above on the refund (listed, processed, not ours, none of this part's own listed), but the
  payment need not be refunded in full; instead: `evidence` of at least 15 characters (400 `VALIDATION_ERROR` otherwise, at every call),
  a `verify` under ten minutes old that found a refund that is not ours (409 `REFUND_VERIFICATION_REQUIRED`; a later verify voids the
  first approval), and **always two different people, whatever the amount** (bound to the refund id; `ops_action` `CV...`). The refund's
  **unclaimed remainder** (its amount less what other parts already closed against it) must cover the part (422 otherwise), so the parts
  closed against one refund never add up to more than it (re-checked under the lock). The part ends `COMPLETED`, `provider_refund_id`
  empty (no unique-index claim), `review_cause = COMPLETED_BY_OTHER_REFUND`, `review_ref` = the refund; the source is blocked
  `REFUNDED_ELSEWHERE`; the wallet is not credited. It cannot be combined with the other two confirmations, and is for withdrawal parts
  only. The system never does this by itself: it is a judgement that the refund was made for this part. If the judgement is wrong the
  restaurant loses the part and there is no undo (`COMPLETED` is terminal). The company cannot pay twice by it **because of the age rule**:
  for a part whose send was never answered (`AMBIGUOUS`) it is refused (409 `REFUND_VERIFICATION_REQUIRED`) within 30 minutes of the last
  send, at every call and again under the lock (the same minimum as a put-back; the same applies to the `confirmPayerRefundedInFull`
  close), since our own lost send may still land at the provider and the payer would hold it and the covering refund. Nothing is sent
  and nothing is credited by the close itself. A provider refund that other parts are already closed against is never adopted by a plain
  `mark-completed` of another part (422): the same money would stand for both.
  The **plain `mark-completed`** (the refund is for exactly the part's amount, so the part adopts it) keeps the same minimum age: for an `AMBIGUOUS`
  part it is refused (409 `REFUND_VERIFICATION_REQUIRED`) within 30 minutes of the last send, before the transaction and again under the locks,
  because the refund may be one made by hand with our own lost send still to land. A part that failed another way is not held by it.
- **`retry`**: **only for a refund that has nothing to do with the part** (and then only while Razorpay can still take it). Never for a
  refund that covers the part: the part would be sent a second time and the payer would hold both (payment 1000, 500 refunded by hand for
  a part of 400, retry: the payer is refunded 900 against 400 owed). The refusals say so.

*What remains for engineering:* a refund made by hand that is worth the part or more, is **unrelated** to it (so it must not close the part),
and Razorpay has **no room** left for the part (payment 1000 fully withdrawn as one part, then an unrelated refund of 10 is excluded and put back:
fine, but an unrelated 500 against a part of 400 on a payment of 1000 that cannot take 400 more). It can be neither excluded (not below the
part), nor closed (it is not the part's money), nor retried (no room): the part stays in review until someone with access corrects the books.
A hand refund 0.01 **below** the part passes the exclusion rule: that is a judgement the rule cannot catch (see D-110).

**Configuration fault while sending or settling.** *Sending:* two different payments in a row that Razorpay says it does
not know, with no answer about another payment in between, stop the send run (an answer in between starts the count again).
*Settling:* the rejected queue is read in full (a page of a hundred, the one read least recently first), and the payments
Razorpay did not know are held back. If it answers normally about **any** other refund on the page, the keys are right and
the held ones are payments that are gone: each goes to review once (`PAYMENT_GONE`). Only if nothing on the page was
answered and two or more different payments were unknown is it a fault: one throttled ERROR `Configuration fault: the provider
does not know payments ...`, nothing is put back, blocked or sent, and those refunds are moved behind the others in the queue
(`settle_held_at`, not `updated_at`, which is when they were rejected) so they cannot hold its head for ever. A resend whose
lookup answers 404 is not sent either (the earlier send may be at the real account). Refunds refused as unknown during a
fault are sent again once the keys are right (table above), not put back.

**After a reversal** the job lists the payment's refunds again at ten minutes, one hour and six hours, then daily up to
fourteen days and twelve hours (`PaymentJobs.auditReversedRefunds`, every minute; a refund that turns up later than that is **not** detected; the schedule comes from `reversed_at` and
`verified_at`, so a restart skips nothing; it stops for the run on a 429 or refused keys, like the other jobs, and asks again
next run). It looks for a refund of ours, and also for what the reversal itself required: another refund on the list, or
more refunded than ours explain (our own refunds that carry a provider refund id and are not on the list yet count as
explained: a refund of ours made a moment ago shows in the payment's figure before it shows in the list). One look that
disagrees is only noted (`verified_result = SUSPECT`, `verified_at` = when it was first seen); the payment is read again at least
five minutes later, whatever the schedule says (`ReversalAuditScheduleTest`), and only if it still disagrees is it an alarm.
Finding one of ours logs **`CRITICAL refund N was REVERSED but provider
refund X exists: restaurant credited twice`**, a payer refunded another way **`CRITICAL refund N was REVERSED but the payer
was refunded another way (...)`**; either marks the payment for a person, publishes `WithdrawalDoubleCredit`, and
**pauses that outlet's withdrawals** (503 `WITHDRAWALS_PAUSED`; spending stays allowed; a reminder ERROR every six hours)
until `POST /api/v1/admin/refunds/{id}/resolve-late-success` (`REFUND_OPERATE`, a note; moves no money, so adjust the
wallet first if it should be). It claws nothing back (the balance may be spent). A refund in review after an
ambiguous send is read again hourly for a week (`reconcileAmbiguousRefunds`) and adopted if Razorpay made it.

**One provider refund completes one of ours.** `refund.provider_refund_id` is uniquely indexed (V48) and adoption,
mark-completed and the legacy amount-and-time match all check, inside the locked transaction, that no other refund of ours
holds it; if one does, the refund goes to review (`PROVIDER_REFUND_TAKEN`) and is not completed.

**Two dispute refunds at once.** `WalletService.creditDisputeRefund` decides after the wallet lock from **one `FOR SHARE` read of the
order's own wallet entries** (index `ix_wallet_txn_order` forced), on the connection the transaction holds (a second one
would let as many concurrent refunds as the pool has connections wait for each other). It must never lock by the new
reference: a locking read of a key that does not exist yet takes a shared lock on the gap, and two refunds on two wallets
with neighbouring references then each need to insert into the other's gap, which InnoDB settles by rolling one back
(`Deadlock found when trying to get lock`) every time they overlap. The unique reference stays the backstop. A dispute approval
(`DisputeRefundService.supplierApprove`/`opsApprove`, one transaction: the supplier's charge and the credit) that is chosen as
a deadlock loser anyway is run once more (`DeadlockRetry`; only where the approval starts its own transaction, and only once).
If a second deadlock ever shows, look for a new locking read on `wallet_transaction`.

**Circuit breaker.** A refund refused for `INSUFFICIENT_BALANCE` or `CONFIG` in the last thirty minutes pauses
new withdrawals: **503 `WITHDRAWALS_PAUSED`**, nothing debited. Ops must keep a float in the Razorpay account
(E-8). Nothing was attempted, so the client's idempotency key is released, not marked failed: the app's retry with
the same key works once the pause ends (the 503 stays, a 5xx the app keeps its key for).

**Operations** (`REFUND_OPERATE` for `OPS_FINANCE` and `OPS_ADMIN`; reading needs `PAYMENT_INSPECT`; every action
needs a `note` and is audited with the actor). A payment Razorpay does not know is a separate case with its own rules
(next section); the system sends it to review and never puts it back.

**What can be excluded, and what the room rule still covers.** `foreign-refund-not-this-part` (two people) records that listed foreign
refunds are not this part's, under one rule: **every refund named, with those already recorded, is worth strictly less than the part and
all of them together are strictly less** (422 at the first approval, at the second and under the lock; equality is refused: 400 against a
part of 400, 25 + 25 against 50; 399.99 against 400 and 20 + 20 against 50 are allowed). A refund of the part's amount or more is never
"not this part's"; a real double payout was found this way (a payment of 430.50 refunded in full by hand, a part of 50 excluded and put
back). The **room invariant** (everything Razorpay shows refunded, the larger of its `amount_refunded` and the sum of its non-failed
listed refunds, plus this part, must not exceed the payment, else 422 "already been refunded to the payer for at least this amount") now
applies only to foreign refunds that have **not** been validly recorded as not this part's (today: a refund that parts of ours were
closed against): refunds validly excluded are removed from its total, because each is worth less than the part and together they are, so
they cannot be compensation for it, and Razorpay's own room is no measure of what the wallet is owed (payment 1000 fully withdrawn as one
part, then a goodwill refund of 10 by hand: Razorpay refuses the 1000, the part goes to review, two people record the 10 as not this
part's and two people put the 1000 back; the payer keeps the 10). Any other foreign refund, or an `amount_refunded` that ours and the
recorded ones do not explain, is still `FOREIGN_REFUND` (`verify`, `recredit`, `to-wallet`, the system). The `recredit` after an
exclusion always needs two different people and `evidence` (at least 15 characters) **from each of them**, whatever the amount, plus a
fresh `verify`, the minimum age for an ambiguous part and its own read. The minimum age since the last send of a refund with no
`last_sent_at` (legacy) is counted from `sent_at`, else `created_at`, never `updated_at`.

**A part on a payment Razorpay does not know** (`verified_result = UNKNOWN_PAYMENT`). Razorpay's refunds list for an unknown
payment answers 200 with nothing, and only the payment read says unknown. A person's `verify` therefore needs both (an empty or
404 list, and a 404 payment read) **and proof that the keys work right now**: a successful read of a captured payment of ours
made *after* this one, the newest first, at most five, for the same order as our books. No such payment, or a failed read, or
wrong keys, mode or base URL (every payment then looks unknown): **503 `PROVIDER_UNAVAILABLE`**, "the provider's keys could not be
proven", nothing recorded. Proven: `verify` answers `UNKNOWN_PAYMENT`, records it, and audits `REFUND_UNKNOWN_PAYMENT_PROOF`
(with the proving payment). `recredit` and `to-wallet` of such a part need **two different people, always**, each sending
`evidence` (at least 15 characters, which Razorpay account or keys the payment belongs to) and
`confirmPaymentOnOtherAccount: true` (another, retired account; nothing sent from it), plus a `verify` under ten minutes old, the
minimum age since the last send (any kind of failure), `confirmNoProviderRefund`, and a read of Razorpay inside the call that
repeats the proof (409 `REFUND_VERIFICATION_REQUIRED` if it no longer says unknown; if the payment is known after all, the ordinary
proof applies and a foreign refund still refuses it). `ops_action` is `RECREDIT_UNKNOWN` / `TOWALLET_UNKNOWN`. The part ends
`REVERSED`, the source blocked `PAYMENT_UNKNOWN`. Legacy refunds stranded this way (payments of a retired account) are closed
by this. Every step is audited with the bounded evidence (its length, a digest of the whole text and its first
characters) and the confirmations of **both** people, and the `REFUND_OPS_RECREDIT` audit is written in the reversal's own
transaction (it cannot fail after the money has moved). A part that carries a `provider_refund_id` (it was sent through keys
that knew the payment) is refused on this path, 409, and is never put back: check that refund on the account it was made on. **If it
was processed there**, two different people close the part with `mark-completed` naming that refund, with `evidence` (at least 15
characters: the other account and what shows the refund was processed there) and `confirmProcessedOnOtherAccount: true`, on a `verify`
under ten minutes old that said `UNKNOWN_PAYMENT`; the provider is read again inside the call (payment still unknown, keys proven); the
part ends `COMPLETED` (`review_cause = COMPLETED_OTHER_ACCOUNT`, `review_ref` = the refund id; the payer has the money, the wallet is
**not** credited), the source blocked `PAYMENT_UNKNOWN`, `ops_action` `MCU...`; withdrawal parts only. **If it was not processed
there**, nothing here closes it (`recredit` stays refused): it needs engineering (a manual step). A cancellation refund carrying an id
has no exit here. A part put back this way always blocks its source `PAYMENT_UNKNOWN`. The late-success
watch marks a read done when the list itself answers not-found. Residual risk: a payment unknown to *these* keys that lives in a
live account someone can still refund from, which only the operator's evidence and the second person guard against; the newest
payment of all has no later payment to prove the keys with, so it is not decided this way; and if the keys were moved to another
account and payments were made on it since, the proof passes for an old payment of the old account, so an earlier ambiguous send
that later succeeded there would be paid twice and only the two people's evidence can prevent it (a provider key id recorded per
payment at send time would close it: future work). Any refund found on the payment later is detected only within the 14.5-day
late-success watch, and only if the payment is readable by the keys then in use.

| Endpoint | What it does |
|---|---|
| `GET /api/v1/admin/refunds?status=&kind=` | the queue: `NEEDS_REVIEW` and `REJECTED` by default |
| `GET /api/v1/admin/refunds/{id}` | the refund, Razorpay's own refund list read now (ours marked), the wallet balance |
| `POST .../refunds/{id}/verify` | reads Razorpay; adopts ours if found, otherwise records "none of ours", or `FOREIGN_REFUND`, or, for a payment Razorpay does not know and the keys proven, `UNKNOWN_PAYMENT` (503 if the keys cannot be proven). Ends a first approver's pending request (see recredit) |
| `POST .../refunds/{id}/retry` | reads first (adopts if found), else back to `REQUESTED` with attempts reset and the same key |
| `POST .../refunds/{id}/recredit` | puts a withdrawal part back in the wallet. Needs a `verify` under ten minutes old showing none of ours (409 `REFUND_VERIFICATION_REQUIRED`), `confirmNoProviderRefund: true`, and `evidence` for an `AMBIGUOUS` refund, which is also refused until `costonomy.mp.refunds.recredit-min-age` (`PT30M`) after its last send, counted from `refund.last_sent_at` (set at every send; an approval, a verification or a note does not move it, so the second person can complete a request above the threshold; the list may lag; 409 `REFUND_VERIFICATION_REQUIRED`). Refused while a foreign refund is on record (above). For a part verified `UNKNOWN_PAYMENT`: two people always, `evidence` and `confirmPaymentOnOtherAccount` (previous section). Above ₹10,000 (`costonomy.mp.refunds.second-approver-above`) the first call records the request and a different person must make the same call within 24 hours (409 `SECOND_APPROVER_REQUIRED`). The request is void as soon as the refund changes status (a retry, an adoption) or is verified again, so the second person must act within ten minutes of the verification the first used, or ask afresh |
| `POST .../refunds/{id}/to-wallet` | a cancellation refund Razorpay would not make goes to the wallet instead: refund `REVERSED`, a wallet refund `cancel-wallet-{id}`, same gates as recredit (body `{note, confirmNoProviderRefund, evidence?, confirmPaymentOnOtherAccount?}`, the last two for an `UNKNOWN_PAYMENT` part) |
| `POST .../refunds/{id}/mark-completed` | `{providerRefundId, note, confirmPayerRefundedInFull?, evidence?, confirmProcessedOnOtherAccount?}` (the last two: a withdrawal part carrying a refund id on an `UNKNOWN_PAYMENT` payment, two people always, see above): the money reached the payer by hand; Razorpay must list that refund under this payment, processed, for this amount, not used by another refund of ours (422 `REFUND_VERIFICATION_FAILED`). With `confirmPayerRefundedInFull: true` (withdrawal parts): a refund for more than this part closes it when the payment is refunded in full and the refund is not ours, the parts closed against one refund never exceeding it; a second person above ₹10,000 (`awaitingSecondApprover`). With `confirmRefundCoversThisPart: true` (withdrawal parts, `evidence` of at least 15 characters, a fresh `verify` that found a foreign refund): the same without the payment being refunded in full, **always two people**; the refund's unclaimed remainder must cover the part. A refund still `PENDING` at Razorpay is answered "exists but not processed yet"; an id the list does not show says a refund made a moment ago may not be listed until processed. See "When the foreign refund is not this part's" |
| `POST .../refunds/{id}/foreign-refund-not-this-part` | `{note, evidence, providerRefundIds}`: two different people record that the named foreign refunds are not this part's (a fresh `verify` that found a foreign refund; each id must be listed on the payment now, not failed and not ours, each worth less than the part and together less than it, checked at the first approval too: 422 `REFUND_VERIFICATION_FAILED`, nothing recorded; 409 `SECOND_APPROVER_REQUIRED` for the same person twice). Records a judgement, moves no money; the proof then leaves out exactly those ids and amounts |
| `POST .../refunds/{id}/resolve-late-success` | `{note}`: a refund that was put back and then turned up at Razorpay has been dealt with; ends the pause on the outlet's withdrawals. Moves no money |
| `POST .../payments/{id}/returned-outside` | limitation 3 above |
| `POST .../payments/{id}/refund-block` / `refund-unblock` | `{reason}`: correct a block, or stop drawing from a payment |

**Deploying V48.** `V48__withdrawal_failure_reversal.sql` has never been applied outside the tests, so it is edited in place
(there is no V45). Its last statement before the permissions is a **unique index on `refund.provider_refund_id`**, which
fails if any value is held twice, and MySQL does not roll DDL back: the columns added above it would stay and the migration
could not be re-run. Before deploying, on every database:

```sql
select provider_refund_id, count(*) from refund
 where provider_refund_id is not null group by provider_refund_id having count(*) > 1;
```

It must return no rows (none is expected: a provider refund id is only ever set from the provider's answer for one refund);
resolve any it returns first. Any environment that already ran an *earlier* version of this file (none is known to) fails
Flyway's checksum check on this one, and needs `flyway repair` or these additions moved to a V45.

**Settings:** `costonomy.mp.razorpay.refund-window-days` (180), `costonomy.mp.refunds.reversal-min-age` (`PT2M`),
`costonomy.mp.refunds.recredit-min-age` (`PT30M`), `costonomy.mp.refunds.second-approver-above` (`10000.00`),
`costonomy.mp.payments.refund-audit-interval` (`PT10M`), `costonomy.mp.payments.reversal-audit-interval` (`PT1M`), and,
for a local stack only, `costonomy.mp.wallet.withdraw-precheck=false`, which skips the check before the debit so a
reversal can be watched. Under a production profile any explicit value of that setting but `true` (`false`, `off`,
`no`, `0`, blank...) refuses to start.

**Deployment prerequisite: production MySQL must run with `binlog_format=ROW`.** The wallet debit of a withdrawal runs at
READ COMMITTED (so that, once it holds the wallet lock, it reads what the withdrawal ahead of it committed), and MySQL
refuses writes at that isolation level under statement-based binary logging. Check `SELECT @@binlog_format` before
the deploy; a managed MySQL (Cloud SQL, RDS) defaults to ROW, a self-managed one may not.

**Mobile.** The app must map: refund/withdrawal statuses `REJECTED` (being checked) and `REVERSED` (not sent, back in
the wallet) next to `REQUESTED`, `PROCESSING`, `COMPLETED`, `FAILED`, `NEEDS_REVIEW`; wallet entry kinds `DISPUTE_REFUND`
and `WITHDRAWAL_REVERSAL`; the error codes `WITHDRAWAL_EXCEEDS_REFUNDABLE` (422, `details.withdrawableNow`, `blocked`,
`unavailable`, `requested`, `reason`, and the additive `checkedSources`, `uncheckedSources`) and `WITHDRAWALS_PAUSED` (503, not a failed attempt: the
same key may be sent again). A successful withdrawal response also carries `checkedSources` and `uncheckedSources` (additive; null on a replay of one made before).

**Still unverified in test mode (V-5, V-6, V-7, V-8):** how long Razorpay remembers `X-Refund-Idempotency` (no
longer relied on); the refund window; the exact 400 wording for each row of the table (the wording only picks a
label, so a change files a refusal as `REJECTED_OTHER`, which is then held to the same proof as any other refusal: a
refund made another way is caught by the list and `amount_refunded`, not by the words); whether a `failed` refund can later
become `processed` (the daily watch says so if it does); and whether `amount_refunded` includes a pending refund
(the pre-check counts our refunds it might not).
