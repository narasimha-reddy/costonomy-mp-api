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
still decided by a person in the database.

**Fee.** `provider_fee` is Razorpay's `fee`, which already includes GST (`tax` is a part of it).

**Known limitations (older behaviour, not changed by D-109).**
1. A `CREATED` payment past the one-day lookup window whose Razorpay order has only a returned
   attempt (refunded, now read as `RELEASED`) is not expired by the sweep, because the lookup finds
   something: it is asked about every sweep run for good and its order stays a draft.
2. An `AUTHORIZED` payment that Razorpay shows as `refunded` **with** `captured = true` (captured
   and refunded by hand in the dashboard) is ignored as an out-of-order event, so its order can still
   be marked ready and the capture then fails. An ops-only situation.
3. **A payment in review that someone refunded by hand in the Razorpay dashboard has no exit
   through the API.** Razorpay then shows it `refunded` with `captured = true`; the re-check acts on
   neither that nor a partial refund, so the row stays `CANCEL_PENDING` with a daily ERROR and the
   order shows `RETURN_DELAYED`, and `clear-review` sends it straight back to review ("refunded at
   the provider outside Mandi"). No money is at risk. Ops-only runbook, **not exercised by a test;
   have a second person read it before running it**: confirm in the dashboard that
   `amount_refunded` equals the payment's amount and note the refund id, then in one transaction
   (a) `update payment set status = 'FULLY_REFUNDED', captured_amount = authorized_amount,
   refunded_amount = authorized_amount, captured_at = coalesce(captured_at, utc_timestamp(6)),
   review_required_at = null, review_reason = null, version = version + 1 where id = ? and status =
   'CANCEL_PENDING'`, (b) insert the cancellation refund as already done: `payment_id` and `supplier_order_id` (both
   `NOT NULL` with no default, so the insert is rejected without them), `amount` = the payment's
   amount, `reason = 'CANCELLATION'`, `destination = 'ORIGINAL'`, `status = 'COMPLETED'`,
   `provider_refund_id` = the dashboard's, `idempotency_key = 'cancel-order-{supplier_order_id}'`,
   `completed_at`; `attempts`, `created_at`, `updated_at` and `version` have defaults. The unique
   key then stops the job raising a second one. And (c) an `audit_log` row saying who did it and
   why (`action`, `entity_type` = 'PAYMENT', `entity_id` = the payment id, `reason`, `source`;
   `action` and `entity_type` are `NOT NULL`). A
   proper audited "returned outside Mandi" action is planned for the next branch's ops actions.
4. **A refund sent again after an ambiguous answer is not first looked up at Razorpay.** A refund
   whose send timed out or got a 5xx is resent with the same `X-Refund-Idempotency` key. That is
   safe while Razorpay remembers the key; its retention window is unverified, and a refund that
   keeps failing for a long time (an outage, a key rotation) could outlive it, after which a
   partial refund (a wallet withdrawal part) could be repeated. Refund attempts that fail for a
   reason that is not the refund's no longer stop after five, so this window is now bounded only by
   the hourly `FAILED` ERROR. The fix (list the payment's refunds at Razorpay and adopt one whose
   `receipt` or `notes.mandi_refund_id` matches, before any resend) belongs to the next branch
   (withdrawal failure and its verify step).
5. A refund in `NEEDS_REVIEW` has no exit except a person changing the database.

In the dashboard a cancelled UPI order shows a captured payment and a refund, not a
hold. To test by hand against the mock: pay with
`MockPaymentProvider.completeCheckout(orderId, "upi")`; the sandbox checkout does not
offer UPI on this account yet (section 5).
