# Architecture Sequence Diagrams: Intent & Procurement Module

This document details the exact runtime request/response sequence diagrams for all endpoints in the **Intent** and **Procurement** modules.

---

## 1. `POST /api/v1/outlets/{outletId}/intent-items` & `POST /api/v1/intents/{id}/send`
**Description**: Restaurant builds an intent basket and broadcasts a request for quote (RFQ) to local suppliers.

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant Buyer
    participant IC as IntentController
    participant IS as IntentService
    participant NR as NotificationRelay
    participant DB as PostgreSQL (intent, intent_item)

    Restaurant->>IC: POST /api/v1/outlets/{outletId}/intent-items (skuId, quantity, targetPrice)
    IC->>IS: addItemToDraft(outletId, itemPayload)
    IS->>DB: Find or create draft intent for outlet
    IS->>DB: INSERT INTO intent_item (intent_id, sku_id, quantity, target_price)
    IS-->>IC: IntentDraftResponseDto
    IC-->>Restaurant: 201 Created

    Restaurant->>IC: POST /api/v1/intents/{id}/send
    IC->>IS: sendIntent(id, principal)
    IS->>DB: UPDATE intent SET status = SENT, sent_at = NOW() WHERE id = id
    IS->>NR: notifyMatchingSuppliers(intentId)
    IS-->>IC: SentIntentResponseDto
    IC-->>Restaurant: 200 OK (Intent Broadcast Active)
```

---

## 2. `POST /api/v1/intents/{id}/respond` (Supplier Quotation)
**Description**: Supplier views open requests and submits pricing, available stock, and delivery mode.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Seller
    participant SIC as SupplierIntentController
    participant SIS as SupplierIntentService
    participant NR as NotificationRelay
    participant DB as PostgreSQL (intent_response, intent_response_item)

    Supplier->>SIC: POST /api/v1/intents/{id}/respond (lines, deliveryMode, validUntil)
    SIC->>SIS: submitResponse(intentId, storeId, responseDto)
    SIS->>DB: SELECT * FROM intent WHERE id = id AND status = 'SENT'
    alt Intent Expired or Cancelled
        SIS-->>SIC: 400 Bad Request
        SIC-->>Supplier: Error
    else Intent Open
        SIS->>DB: INSERT INTO intent_response (intent_id, supplier_store_id, total_amount, delivery_mode)
        SIS->>DB: INSERT INTO intent_response_item (...)
        SIS->>NR: notifyRestaurantOfQuote(outletId, responseId)
        SIS-->>SIC: ResponseSummaryDto
        SIC-->>Supplier: 201 Created
    end
```

---

## 3. `POST /api/v1/intents/{id}/orders` (Order Creation & Funding)
**Description**: Restaurant accepts a supplier's quote, locks basket pricing, and creates the definitive `supplier_order`.

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant Buyer
    participant IC as IntentController
    participant IOC as IntentOrderCreator
    participant CLS as CreditLedgerService
    participant PS as PaymentService
    participant DB as PostgreSQL (supplier_order, order_lines)

    Restaurant->>IC: POST /api/v1/intents/{id}/orders (responseId, fundingMethod)
    IC->>IOC: createOrderFromResponse(intentId, responseId, fundingMethod)
    IOC->>DB: Verify intent_response validity and lock row
    IOC->>DB: INSERT INTO supplier_order (outlet_id, supplier_store_id, total_amount, status=PENDING_PAYMENT)
    IOC->>DB: INSERT INTO supplier_order_item (...)
    
    alt FundingMethod == CREDIT_LINE
        IOC->>CLS: reserveCredit(outletId, orderId, amount)
        CLS->>DB: INSERT INTO credit_reservation (...)
        IOC->>DB: UPDATE supplier_order SET status = PLACED, payment_status = PAID_BY_CREDIT
    else FundingMethod == RAZORPAY / WALLET
        IOC->>PS: createPaymentIntent(orderId, amount)
        PS->>DB: INSERT INTO payment (order_id, amount, status=PENDING)
    end
    
    IOC-->>IC: OrderCreatedResponseDto
    IC-->>Restaurant: 201 Created (orderId, nextAction)
```

---

## 4. `POST /api/v1/supplier-orders/{id}/preparing` & `POST /api/v1/supplier-orders/{id}/ready`
**Description**: Supplier order fulfillment lifecycle transitions.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Seller
    participant SOC as SupplierOrderController
    participant SOT as SupplierOrderTransitions
    participant ORS as OrderReleaseService
    participant PJ as PaymentJobs
    participant DB as PostgreSQL

    Supplier->>SOC: POST /api/v1/supplier-orders/{id}/preparing
    SOC->>SOT: markPreparing(orderId, principal)
    SOT->>DB: UPDATE supplier_order SET status = PREPARING WHERE id = id AND status = 'PLACED'
    SOT-->>SOC: SupplierOrderDto
    SOC-->>Supplier: 200 OK

    Supplier->>SOC: POST /api/v1/supplier-orders/{id}/ready
    SOC->>SOT: markReady(orderId, principal)
    SOT->>DB: UPDATE supplier_order SET status = READY_FOR_PICKUP WHERE id = id
    Note over SOT,ORS: Payment hold is captured at dispatch or ready
    SOT->>ORS: triggerOrderReady(orderId)
    ORS->>PJ: triggerDeferredCapture(orderId)
    SOT-->>SOC: SupplierOrderDto
    SOC-->>Supplier: 200 OK
```

---

## 5. `POST /api/v1/supplier-orders/{id}/supplier-cancel` (Cancellation & Refund)
**Description**: Supplier backs out of an order; automatically triggers debited UPI/card refunds and restores credit reservations.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Seller
    participant SOC as SupplierOrderController
    participant SOT as SupplierOrderTransitions
    participant CS as CancellationService
    participant CLS as CreditLedgerService
    participant RP as RazorpayPaymentProvider
    participant DB as PostgreSQL

    Supplier->>SOC: POST /api/v1/supplier-orders/{id}/supplier-cancel (reason, note)
    SOC->>SOT: cancelBySupplier(orderId, reason, principal)
    SOT->>DB: SELECT * FROM supplier_order WHERE id = id FOR UPDATE
    SOT->>DB: UPDATE supplier_order SET status = CANCELLED, cancellation_reason = reason
    
    alt Order paid via Credit Line
        SOT->>CLS: releaseReservation(orderId)
        CLS->>DB: UPDATE credit_reservation SET status = RELEASED
    else Order paid via Razorpay
        SOT->>CS: handleCancelledOrderRefund(orderId)
        CS->>DB: SELECT * FROM payment WHERE order_id = orderId
        alt Funds were already captured/debited (UPI/NetBanking)
            CS->>RP: createRefund(providerPaymentId, amount, speed=NORMAL)
            RP-->>CS: RefundId (status: PENDING)
            CS->>DB: INSERT INTO refund (payment_id, provider_refund_id, status=SUBMITTED)
        else Funds only on authorization hold
            CS->>RP: releaseAuthorizationHold(providerPaymentId)
            CS->>DB: UPDATE payment SET status = VOIDED
        end
    end
    
    SOT-->>SOC: CancelledOrderResponseDto
    SOC-->>Supplier: 200 OK
```

---

## 6. `POST /api/v1/supplier-orders/{id}/weights` (Catch-Weight Packing & Re-Weighing)
**Description**: Supplier weighs perishable catch-weight items at packing/dispatch; applies ±10% tolerance band, executes atomic wallet delta adjustments, and updates statutory payable baselines without platform absorption.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Warehouse / Dispatcher
    participant SOC as SupplierOrderController
    participant SOS as SupplierOrderService
    participant WS as WalletService
    participant AS as AuditService
    participant OB as OutboxService
    participant DB as MySQL (supplier_order, supplier_order_item, wallet)

    Supplier->>SOC: POST /api/v1/supplier-orders/{id}/weights (weights: [{supplierOrderItemId, weight}])
    SOC->>SOS: recordDispatchWeights(actorId, id, request)
    SOS->>DB: SELECT * FROM supplier_order WHERE id = id FOR UPDATE
    
    loop For each catch-weight item
        SOS->>SOS: Apply ±10% tolerance band (maxBillableWeight = baseline * 1.10)
        alt Overweight > Baseline
            SOS->>WS: balanceOf(outletId)
            alt Wallet balance insufficient for overweight surcharge
                SOS->>SOS: Clamp billableWeight to committed baseline (Anti-Platform Absorption)
            end
        end
        SOS->>DB: UPDATE supplier_order_item SET dispatched_weight, weight_delta_amount, line_total
    end
    
    SOS->>DB: Recalculate subtotal, gst_amount, final_payable_amount
    
    alt Net Adjustment > 0 (Under-weight refund to buyer)
        SOS->>WS: recordAdjustment(outletId, orderId, CREDIT, refundAmount, reason)
        WS->>DB: UPDATE wallet SET balance = balance + refundAmount
        WS->>DB: INSERT INTO wallet_transaction (direction=CREDIT, kind=ORDER_ADJUSTMENT)
    else Net Adjustment < 0 (Over-weight surcharge debit)
        SOS->>WS: recordAdjustment(outletId, orderId, DEBIT, surchargeAmount, reason)
        alt Debit Fails (Balance Insufficient)
            WS-->>SOS: false (debit unsuccessful)
            SOS-->>SOC: 400 Bad Request (Insufficient wallet balance for overweight)
            SOC-->>Supplier: Error response (Transaction Rolled Back)
        else Debit Succeeded
            WS->>DB: UPDATE wallet SET balance = balance - surchargeAmount
            WS->>DB: INSERT INTO wallet_transaction (direction=DEBIT, kind=ORDER_ADJUSTMENT)
        end
    end
    
    SOS->>AS: record("ORDER_WEIGHTS_RECORDED")
    SOS->>OB: publish("CatchWeightReconciled")
    SOS-->>SOC: SupplierOrderResponse
    SOC-->>Supplier: 200 OK (Weights Recorded & Financials Reconciled)
```

---

## 7. `POST /api/v1/supplier-stores/{storeId}/subscriptions/generate-orders` (Daily Automated Replenishment)
**Description**: Daily cron or store trigger that evaluates active subscriptions due for fulfillment, performs atomic wallet debiting or credit reservation, and advances due dates only upon confirmed funding.

```mermaid
sequenceDiagram
    autonumber
    actor System as Cron Job / Store Ops
    participant SC as SubscriptionController
    participant SS as SubscriptionService
    participant WS as WalletService
    participant CLS as CreditLedgerService
    participant OB as OutboxService
    participant DB as MySQL (subscription, supplier_order, wallet, credit)

    System->>SC: POST /api/v1/supplier-stores/{storeId}/subscriptions/generate-orders (targetDate)
    SC->>SS: generateDailyOrders(actorId, storeId, targetDate)
    SS->>DB: SELECT * FROM subscription WHERE supplier_store_id = storeId AND status = 'ACTIVE'
    
    loop For each due subscription
        SS->>DB: Check existing order for date (idempotency guard)
        SS->>DB: INSERT INTO supplier_order (status=DRAFT, is_subscription_order=true)
        SS->>DB: INSERT INTO supplier_order_item (...)
        
        alt PaymentMethod == WALLET
            SS->>WS: debitFor(outletId, orderId, totalAmount)
            alt Wallet Debited Successfully
                WS->>DB: UPDATE wallet SET balance = balance - totalAmount
                SS->>DB: UPDATE supplier_order SET status = CONFIRMED, payment_status = COMPLETED
                SS->>DB: Advance subscription next_delivery_date
            else Insufficient Wallet Balance
                WS-->>SS: false
                SS->>DB: Keep order in DRAFT (unfunded)
                SS->>OB: publish("SubscriptionFundingFailed", payload)
                Note over SS,DB: next_delivery_date is NOT advanced (kept for retry)
            end
        else PaymentMethod == CREDIT
            SS->>CLS: reserve(agreementId, orderId, null, totalAmount)
            alt Credit Reserved
                SS->>DB: UPDATE supplier_order SET status = CONFIRMED, payment_status = PENDING
                SS->>DB: Advance subscription next_delivery_date
            else Credit Limit Exceeded
                SS->>OB: publish("SubscriptionFundingFailed", payload)
            end
        end
    end
    
    SS-->>SC: GenerateOrdersResponse(generatedOrderIds)
    SC-->>System: 200 OK
```
