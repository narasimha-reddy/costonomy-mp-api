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
