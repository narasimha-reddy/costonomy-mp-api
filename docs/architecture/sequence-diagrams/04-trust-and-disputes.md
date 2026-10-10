# Architecture Sequence Diagrams: Trust, Receiving & Disputes Module

This document details the exact runtime request/response sequence diagrams for all endpoints in the **Trust, Disputes, and Receiving** module.

---

## 1. `POST /api/v1/supplier-orders/{orderId}/receive` (Receiving Check-In & Doorstep Escrow)
**Description**: Restaurant kitchen receives order goods at the door, records accepted vs rejected quantities per line item with rejection reasons, generates instant wallet refunds for rejected goods, issues statutory GST Credit Notes (Section 34 CGST Act), and reconciles final payable amounts.

```mermaid
sequenceDiagram
    autonumber
    actor Kitchen as Restaurant Staff / Chef
    participant TC as TrustController
    participant RS as ReceivingService
    participant TD as TrustDirectory
    participant TIS as TaxInvoiceService
    participant WS as WalletService
    participant AS as AuditService
    participant OB as OutboxService
    participant DB as MySQL (receiving, supplier_order, credit_note, wallet)

    Kitchen->>TC: POST /api/v1/supplier-orders/{orderId}/receive (answers: [{orderItemId, receivedQty, rejectedQty, reason}])
    TC->>RS: receive(actorId, orderId, request)
    RS->>TD: order(orderId)
    
    loop For each item answer
        RS->>TD: recordDoorstepReconciliation(item, receivedQty, rejectedQty, reason, lineRefund)
        TD->>DB: UPDATE supplier_order_item SET doorstep_accepted_qty, doorstep_rejected_qty, doorstep_refund_amount
    end
    
    alt totalRefundAmount > 0 (Items Rejected at Doorstep)
        RS->>TD: updateOrderFinancialReconciliation(orderId, totalRefundAmount)
        TD->>DB: UPDATE supplier_order SET doorstep_refund_amount, final_payable_amount
        RS->>TIS: generateCreditNoteForRejection(orderId, "DOORSTEP_REJECTION")
        TIS->>DB: INSERT INTO credit_note (supplier_order_id, credit_note_number, taxable_amount, gst_amount)
        RS->>WS: recordAdjustment(outletId, orderId, CREDIT, totalRefundAmount, "Doorstep rejection refund")
        WS->>DB: UPDATE wallet SET balance = balance + totalRefundAmount
        WS->>DB: INSERT INTO wallet_transaction (direction=CREDIT, kind=ORDER_ADJUSTMENT)
    end
    
    RS->>TD: completeOrder(orderId)
    TD->>DB: UPDATE supplier_order SET status = COMPLETED, final_payable_amount = reconciled
    RS->>AS: record("ORDER_RECEIVED", status=COMPLETED)
    RS->>OB: publish("ReceivingCompleted", {instantRefundAmount, hasDiscrepancy})
    RS-->>TC: ReceivingResponse(totalAccepted, totalRejected, creditNoteNumber)
    TC-->>Kitchen: 200 OK
```

---

## 2. `POST /api/v1/supplier-orders/{orderId}/disputes` & `POST /api/v1/disputes/{id}/messages`
**Description**: Restaurant opens a formal dispute for bad quality, missing goods, or delivery issues, and engages in communication.

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant Buyer
    actor Supplier as Supplier Seller
    participant TC as TrustController
    participant DS as DisputeService
    participant NR as NotificationRelay
    participant DB as PostgreSQL (dispute, dispute_message)

    Restaurant->>TC: POST /api/v1/supplier-orders/{orderId}/disputes (category, reason, evidence)
    TC->>DS: createDispute(orderId, disputeDto, principal)
    DS->>DB: INSERT INTO dispute (order_id, category, status=OPEN, requested_amount)
    DS->>DB: INSERT INTO dispute_message (dispute_id, sender_id, text)
    DS->>NR: notifySupplier(storeId, disputeId)
    DS-->>TC: DisputeDetailDto
    TC-->>Restaurant: 201 Created (Dispute ID)

    Supplier->>TC: POST /api/v1/disputes/{id}/messages (replyText)
    TC->>DS: postMessage(disputeId, replyText, principal)
    DS->>DB: INSERT INTO dispute_message (dispute_id, sender_id, text)
    DS->>NR: notifyRestaurant(outletId, disputeId)
    DS-->>TC: DisputeMessageDto
    TC-->>Supplier: 201 Created
```

---

## 3. `POST /api/v1/disputes/{id}/refund-request` & `POST /api/v1/dispute-refunds/{id}/approve`
**Description**: Restaurant requests monetary compensation on a dispute; approval triggers wallet credits and supplier payout deductions.

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant Buyer
    actor Supplier as Supplier Seller
    participant TC as TrustController
    participant DRS as DisputeRefundService
    participant SRL as SupplierRefundLedger
    participant WS as WalletService
    participant DB as PostgreSQL (dispute_refund, wallet, settlement)

    Restaurant->>TC: POST /api/v1/disputes/{id}/refund-request (amount, justification)
    TC->>DRS: requestRefund(disputeId, amount, principal)
    DRS->>DB: SELECT * FROM dispute WHERE id = disputeId
    DRS->>DB: Verify amount <= remaining refundable limit
    DRS->>DB: INSERT INTO dispute_refund (dispute_id, amount, status=PENDING_APPROVAL)
    DRS-->>TC: DisputeRefundDto
    TC-->>Restaurant: 201 Created

    Supplier->>TC: POST /api/v1/dispute-refunds/{id}/approve
    TC->>DRS: approveRefund(refundId, principal)
    DRS->>DB: SELECT * FROM dispute_refund WHERE id = refundId FOR UPDATE
    DRS->>DB: UPDATE dispute_refund SET status = APPROVED, approved_at = NOW()
    
    Note over DRS,WS: Credit restaurant wallet immediately
    DRS->>WS: creditDisputeRefund(outletId, amount, refundId)
    WS->>DB: INSERT INTO wallet_transaction (wallet_id, amount, direction=CREDIT, kind=DISPUTE_REFUND)
    WS->>DB: UPDATE wallet SET balance = balance + amount
    
    Note over DRS,SRL: Deduct from supplier payout ledger
    DRS->>SRL: recordDeduction(storeId, amount, refundId)
    SRL->>DB: INSERT INTO supplier_deduction (store_id, amount, reason='DISPUTE_REFUND')
    
    DRS-->>TC: RefundApprovedResultDto
    TC-->>Supplier: 200 OK
```

---

## 4. `POST /api/v1/supplier-orders/{orderId}/rating`
**Description**: Restaurant submits quality, fulfillment speed, and service rating for the supplier store.

```mermaid
sequenceDiagram
    autonumber
    actor Restaurant as Restaurant Buyer
    participant TC as TrustController
    participant RS as RatingService
    participant DB as PostgreSQL (rating, supplier_store)

    Restaurant->>TC: POST /api/v1/supplier-orders/{orderId}/rating (score: 1-5, comment)
    TC->>RS: submitRating(orderId, ratingDto, principal)
    RS->>DB: Verify order is in DELIVERED/RECEIVED state
    RS->>DB: Check if order already rated
    alt Already rated
        RS-->>TC: 409 Conflict
        TC-->>Restaurant: Error
    else First rating
        RS->>DB: INSERT INTO rating (order_id, supplier_store_id, score, comment, status=APPROVED)
        RS->>DB: Recompute store average: UPDATE supplier_store SET rating_average = AVG(...)
        RS-->>TC: RatingDto
        TC-->>Restaurant: 201 Created
    end
```
