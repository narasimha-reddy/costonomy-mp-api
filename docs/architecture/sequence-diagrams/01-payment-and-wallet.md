# Architecture Sequence Diagrams: Payments & Wallet Module

This document details the exact runtime request/response sequence diagrams for all endpoints in the **Payment** and **Wallet** modules.

---

## 1. `GET /api/v1/supplier-orders/{orderId}/payment-intent`
**Description**: Fetches or initializes payment intent details (Razorpay Order ID, amount in paise, currency, key) for a given supplier order so the client can trigger checkout.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant Client
    participant PC as PaymentController
    participant PS as PaymentService
    participant RP as RazorpayPaymentProvider
    participant RZ as Razorpay API
    participant DB as PostgreSQL (payments, orders)

    Client->>PC: GET /api/v1/supplier-orders/{orderId}/payment-intent
    PC->>PS: getOrCreateIntentForOrder(orderId, principal)
    PS->>DB: SELECT * FROM supplier_order WHERE id = orderId
    alt Order already paid or invalid state
        PS-->>PC: 400 Bad Request / 409 Conflict
        PC-->>Client: Error response
    else Valid unpaid order
        PS->>DB: SELECT * FROM payment WHERE order_id = orderId
        alt Active intent exists
            PS-->>PC: Return existing intent DTO
        else No active intent
            PS->>RP: createOrder(orderId, amountPaise, currency)
            RP->>RZ: POST /v1/orders (amount, currency, notes)
            RZ-->>RP: 200 OK (razorpay_order_id)
            RP-->>PS: ProviderOrder(razorpay_order_id)
            PS->>DB: INSERT INTO payment (order_id, provider_order_id, amount, status=PENDING)
            PS-->>PC: PaymentIntentResponse DTO
        end
        PC-->>Client: 200 OK (keyId, orderId, amount, currency)
    end
```

---

## 2. `POST /api/v1/payments/{id}/confirm`
**Description**: Confirms payment completion after client checkout, verifying HMAC signature or provider authorization.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant Client
    participant PC as PaymentController
    participant PS as PaymentService
    participant RP as RazorpayPaymentProvider
    participant RZ as Razorpay API
    participant ORS as OrderReleaseService
    participant DB as PostgreSQL

    Client->>PC: POST /api/v1/payments/{id}/confirm (signature, paymentId)
    PC->>PS: confirmPayment(paymentId, confirmationPayload)
    PS->>DB: SELECT * FROM payment WHERE id = id FOR UPDATE
    PS->>RP: verifyPaymentSignature(providerOrderId, providerPaymentId, signature)
    alt Signature Invalid
        RP-->>PS: Verification failed
        PS->>DB: UPDATE payment SET status = FAILED
        PS-->>PC: 400 Bad Request (Invalid signature)
        PC-->>Client: Error
    else Signature Valid
        PS->>RP: fetchPayment(providerPaymentId)
        RP->>RZ: GET /v1/payments/{providerPaymentId}
        RZ-->>RP: 200 OK (status: authorized/captured)
        PS->>DB: UPDATE payment SET status = AUTHORIZED, provider_payment_id = providerPaymentId
        PS->>ORS: onPaymentAuthorized(orderId)
        ORS->>DB: UPDATE supplier_order SET payment_status = PAID, status = PLACED
        PS-->>PC: PaymentConfirmationResponse DTO
        PC-->>Client: 200 OK (Payment confirmed)
    end
```

---

## 3. `GET /api/v1/payments/{id}` & `GET /api/v1/payments/{id}/refunds`
**Description**: Queries the live payment details or refund history associated with a payment record.

```mermaid
sequenceDiagram
    autonumber
    actor Client as User / Admin
    participant PC as PaymentController
    participant PS as PaymentService
    participant RS as RefundService
    participant DB as PostgreSQL

    Client->>PC: GET /api/v1/payments/{id}
    PC->>PS: getPaymentById(id, principal)
    PS->>DB: SELECT * FROM payment WHERE id = id
    PS-->>PC: PaymentResponse DTO
    PC-->>Client: 200 OK

    Client->>PC: GET /api/v1/payments/{id}/refunds
    PC->>RS: listRefundsForPayment(paymentId)
    RS->>DB: SELECT * FROM refund WHERE payment_id = id ORDER BY created_at DESC
    RS-->>PC: List of RefundDto
    PC-->>Client: 200 OK
```

---

## 4. `POST /api/v1/webhooks/razorpay`
**Description**: Ingests asynchronous webhook events from Razorpay (payment authorized, captured, failed, refund processed).

```mermaid
sequenceDiagram
    autonumber
    participant RZ as Razorpay Webhook Service
    participant PWC as PaymentWebhookController
    participant PWS as PaymentWebhookService
    participant PS as PaymentService
    participant RS as RefundService
    participant DB as PostgreSQL

    RZ->>PWC: POST /api/v1/webhooks/razorpay (Headers: X-Razorpay-Signature)
    PWC->>PWS: handleWebhook(payload, signature)
    PWS->>PWS: verifyHmacSha256(payload, secret, signature)
    alt Signature Mismatch
        PWS-->>PWC: Signature Verification Exception
        PWC-->>RZ: 400 Bad Request
    else Signature Valid
        PWC-->>RZ: 200 OK (Acknowledged immediately)
        alt Event == payment.authorized
            PWS->>PS: markAuthorized(providerPaymentId, providerOrderId)
            PS->>DB: UPDATE payment SET status = AUTHORIZED WHERE provider_order_id = ...
        else Event == payment.failed
            PWS->>PS: markFailed(providerPaymentId, reason)
            PS->>DB: UPDATE payment SET status = FAILED
        else Event == refund.processed
            PWS->>RS: markRefundProcessed(providerRefundId)
            RS->>DB: UPDATE refund SET status = SUCCESS WHERE provider_refund_id = ...
        end
    end
```

---

## 5. `GET /api/v1/outlets/{outletId}/wallet`
**Description**: Retrieves wallet ledger balance, pending withdrawals, and statement entries.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant Client
    participant WC as WalletController
    participant WS as WalletService
    participant DB as PostgreSQL (wallets, wallet_transactions)

    Client->>WC: GET /api/v1/outlets/{outletId}/wallet
    WC->>WS: getWalletOverview(outletId, principal)
    WS->>DB: SELECT * FROM wallet WHERE outlet_id = outletId
    WS->>DB: SELECT * FROM wallet_transaction WHERE wallet_id = wallet.id ORDER BY created_at DESC LIMIT 50
    WS-->>WC: WalletSummaryDto (balance, currency, recentEntries)
    WC-->>Client: 200 OK
```

---

## 6. `POST /api/v1/outlets/{outletId}/wallet/top-up`
**Description**: Top-up wallet funds (available in mock/sandbox; restricted in production environments with real payment gateways).

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant Client
    participant WC as WalletController
    participant PPG as ProductionProviderGuard
    participant WS as WalletService
    participant DB as PostgreSQL

    Client->>WC: POST /api/v1/outlets/{outletId}/wallet/top-up (amount)
    WC->>PPG: checkSandboxAllowed()
    alt Real Money Provider Configured (Production)
        PPG-->>WC: 403 Forbidden (Free top-up disabled)
        WC-->>Client: 403 Forbidden
    else Local / Test Mock
        WC->>WS: topUpWallet(outletId, amount)
        WS->>DB: SELECT * FROM wallet WHERE outlet_id = outletId FOR UPDATE
        WS->>DB: INSERT INTO wallet_transaction (wallet_id, amount, direction=CREDIT, kind=TOP_UP)
        WS->>DB: UPDATE wallet SET balance = balance + amount
        WS-->>WC: Updated WalletDto
        WC-->>Client: 200 OK
    end
```

---

## 7. `POST /api/v1/outlets/{outletId}/wallet/withdraw`
**Description**: Requests a wallet withdrawal reversal back to the source bank account/card.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant Client
    participant WC as WalletController
    participant WWS as WalletWithdrawalService
    participant WRS as WithdrawalReversalService
    participant RP as RazorpayPaymentProvider
    participant DB as PostgreSQL

    Client->>WC: POST /api/v1/outlets/{outletId}/wallet/withdraw (amount, idempotencyKey)
    WC->>WWS: requestWithdrawal(outletId, amount, key)
    WWS->>DB: SELECT * FROM wallet WHERE outlet_id = outletId FOR UPDATE
    alt Insufficient Balance
        WWS-->>WC: 400 Bad Request
        WC-->>Client: Error
    else Balance Available
        WWS->>DB: INSERT INTO wallet_transaction (amount, direction=DEBIT, kind=WITHDRAWAL)
        WWS->>DB: UPDATE wallet SET balance = balance - amount
        WWS->>RP: initiateRefundToSource(sourcePaymentId, amount)
        alt Immediate Provider Failure
            RP-->>WWS: Error (e.g. Bank Rejected)
            WWS->>WRS: reverseFailedWithdrawal(txId, proof)
            WRS->>DB: UPDATE wallet SET balance = balance + amount
            WWS-->>WC: 502 Bad Gateway / Provider error
            WC-->>Client: Error with retry explanation
        else Provider Accepted
            RP-->>WWS: Refund ID (status: PENDING)
            WWS-->>WC: WithdrawalInitiatedDto
            WC-->>Client: 202 Accepted
        end
    end
```

---

## 8. `POST /api/v1/outlets/{outletId}/wallet/bank-payout`
**Description**: Initiates payout from general wallet balance (including catch-weight refunds, doorstep rejections, and direct top-ups) directly to the restaurant's verified bank account via IMPS/NEFT.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant Finance / Owner
    participant WC as WalletController
    participant AC as AccessControlService
    participant IS as IdempotencyService
    participant BPS as WalletBankPayoutService
    participant WS as WalletService
    participant OB as OutboxService
    participant DB as MySQL (wallet, wallet_transaction, outbox_event)

    Client->>WC: POST /api/v1/outlets/{outletId}/wallet/bank-payout (amount, account, ifsc, beneficiaryName)
    WC->>AC: requireScoped(actorId, WALLET_WITHDRAW, OUTLET, outletId)
    WC->>IS: execute(actorId, "wallet.bank-payout", idempotencyKey, payload)
    IS->>BPS: initiatePayout(actorId, outletId, request, key)
    
    BPS->>WS: lock(outletId)
    WS->>DB: SELECT * FROM wallet WHERE outlet_id = outletId FOR UPDATE
    
    alt Balance < Requested Amount
        WS-->>BPS: Insufficient balance
        BPS-->>WC: 400 Bad Request (Insufficient funds)
        WC-->>Client: Error response
    else Balance Sufficient
        BPS->>WS: debitBankPayout(outletId, payoutRef, amount, maskedAccount)
        WS->>DB: UPDATE wallet SET balance = balance - amount, version = version + 1 WHERE id = walletId
        WS->>DB: INSERT INTO wallet_transaction (direction=DEBIT, kind=BANK_PAYOUT, amount, ref=payoutRef)
        BPS->>OB: publish("WalletBankPayoutInitiated", payload)
        OB->>DB: INSERT INTO outbox_event (topic="WalletBankPayoutInitiated", payload)
        BPS-->>IS: BankPayoutResponse(INITIATED, balanceAfter, maskedAccount)
        IS-->>WC: BankPayoutResponse
        WC-->>Client: 200 OK (Transfer Initiated)
    end
```
