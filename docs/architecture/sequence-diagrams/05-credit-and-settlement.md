# Architecture Sequence Diagrams: Credit & Settlement Module

This document details the exact runtime request/response sequence diagrams for all endpoints in the **Credit Ledger** and **Supplier Settlement** modules.

---

## 1. `POST /api/v1/restaurants/{id}/credit/reserve` & `utilize`
**Description**: Two-phase credit ledger lifecycle: reservation during order placement, followed by utilization upon delivery or release upon cancellation.

```mermaid
sequenceDiagram
    autonumber
    actor Service as Order Service / Client
    participant CC as CreditController
    participant CLS as CreditLedgerService
    participant DB as PostgreSQL (credit_line, credit_reservation, credit_transaction)

    Service->>CC: POST /api/v1/restaurants/{id}/credit/reserve (orderId, amount)
    CC->>CLS: reserveCredit(restaurantId, orderId, amount)
    CLS->>DB: SELECT * FROM credit_line WHERE restaurant_id = id FOR UPDATE
    alt Available Credit < Amount
        CLS-->>CC: 400 Bad Request (Credit limit exceeded)
        CC-->>Service: Error
    else Credit Available
        CLS->>DB: INSERT INTO credit_reservation (order_id, amount, status=ACTIVE)
        CLS->>DB: UPDATE credit_line SET reserved_amount = reserved_amount + amount
        CLS-->>CC: CreditReservationDto
        CC-->>Service: 200 OK (Reserved)
    end

    Note over Service,DB: On order delivery
    Service->>CC: POST /api/v1/restaurants/{id}/credit/utilize (orderId)
    CC->>CLS: utilizeCredit(orderId)
    CLS->>DB: SELECT * FROM credit_reservation WHERE order_id = orderId FOR UPDATE
    CLS->>DB: UPDATE credit_reservation SET status = UTILIZED
    CLS->>DB: UPDATE credit_line SET reserved_amount = reserved_amount - amount, used_amount = used_amount + amount
    CLS->>DB: INSERT INTO credit_transaction (direction=DEBIT, amount, kind=ORDER_UTILIZATION)
    CLS-->>CC: CreditUtilizationDto
    CC-->>Service: 200 OK
```

---

## 2. `POST /api/v1/restaurants/{id}/credit/repay`
**Description**: Restaurant repays outstanding credit balance via bank transfer or gateway.

```mermaid
sequenceDiagram
    autonumber
    actor Admin as Finance Admin / Payment Gateway
    participant CC as CreditController
    participant CLS as CreditLedgerService
    participant DB as PostgreSQL

    Admin->>CC: POST /api/v1/restaurants/{id}/credit/repay (amount, paymentRef)
    CC->>CLS: recordRepayment(restaurantId, amount, paymentRef)
    CLS->>DB: SELECT * FROM credit_line WHERE restaurant_id = id FOR UPDATE
    CLS->>DB: UPDATE credit_line SET used_amount = used_amount - amount
    CLS->>DB: INSERT INTO credit_transaction (direction=CREDIT, amount, kind=REPAYMENT, ref=paymentRef)
    CLS-->>CC: RepaymentReceiptDto
    CC-->>Admin: 200 OK
```

---

## 3. `GET /api/v1/supplier-stores/{storeId}/settlements` & Breakdown
**Description**: Supplier views settlement periods, gross sales, commission deductions, dispute withholdings, and net payout.

```mermaid
sequenceDiagram
    autonumber
    actor Supplier as Supplier Seller
    participant SC as SettlementController
    participant SS as SettlementService
    participant DB as PostgreSQL (settlement, supplier_order, supplier_deduction)

    Supplier->>SC: GET /api/v1/supplier-stores/{storeId}/settlements
    SC->>SS: listSettlements(storeId)
    SS->>DB: SELECT * FROM settlement WHERE supplier_store_id = storeId ORDER BY period_end DESC
    SS-->>SC: List of SettlementSummaryDto
    SC-->>Supplier: 200 OK

    Supplier->>SC: GET /api/v1/settlements/{id}
    SC->>SS: getSettlementDetails(id, principal)
    SS->>DB: SELECT * FROM settlement WHERE id = id
    SS->>DB: SELECT * FROM supplier_order WHERE settlement_id = id
    SS->>DB: SELECT * FROM supplier_deduction WHERE settlement_id = id
    SS-->>SC: SettlementDetailDto (grossSales, commission, deductions, netPayout, status)
    SC-->>Supplier: 200 OK
```

---

## 4. `POST /api/v1/admin/settlements/{id}/approve` & `/paid`
**Description**: Operations finance team approves verified payout calculations and tracks bank release.

```mermaid
sequenceDiagram
    autonumber
    actor Finance as Ops Finance Admin
    participant SC as SettlementController
    participant SS as SettlementService
    participant DB as PostgreSQL

    Finance->>SC: POST /api/v1/admin/settlements/{id}/approve
    SC->>SS: approveSettlement(id, principal)
    SS->>DB: SELECT * FROM settlement WHERE id = id FOR UPDATE
    SS->>DB: UPDATE settlement SET status = APPROVED, approved_by = principal.id
    SS-->>SC: SettlementDto
    SC-->>Finance: 200 OK

    Finance->>SC: POST /api/v1/admin/settlements/{id}/paid (utrNumber, paymentDate)
    SC->>SS: markSettlementPaid(id, utrNumber, paymentDate)
    SS->>DB: UPDATE settlement SET status = PAID, utr_number = utrNumber, paid_at = paymentDate
    SS-->>SC: SettlementDto
    SC-->>Finance: 200 OK
```

---

## 5. `GET /api/v1/supplier-orders/{id}/tax-invoice` (Statutory GST Billing)
**Description**: Generates statutory GST B2B tax invoice (Section 31 CGST Act) on delivered/completed orders, verifying scoped access control, calculating CGST/SGST/IGST breakdown, and rendering printable HTML/PDF.

```mermaid
sequenceDiagram
    autonumber
    actor Client as Restaurant / Supplier / Admin
    participant TIC as TaxInvoiceController
    participant AC as AccessControlService
    participant TIS as TaxInvoiceService
    participant DB as MySQL (tax_invoice, supplier_order, supplier_store, outlet)

    Client->>TIC: GET /api/v1/supplier-orders/{id}/tax-invoice?format=HTML
    TIC->>AC: requireAnyScoped(actorId, ORDER_VIEW, [OUTLET, SUPPLIER_STORE])
    TIC->>TIS: getOrGenerateInvoice(actorId, orderId)
    TIS->>DB: SELECT * FROM supplier_order WHERE id = orderId
    
    alt Order Status != DELIVERED and != COMPLETED
        TIS-->>TIC: 400 Bad Request (Invoice requires delivered goods)
        TIC-->>Client: Error
    else Order Delivered/Completed
        TIS->>DB: SELECT * FROM tax_invoice WHERE supplier_order_id = orderId
        alt Invoice already generated
            TIS->>DB: Return existing TaxInvoice
        else First generation
            TIS->>TIS: Determine intra-state (CGST+SGST) vs inter-state (IGST) from GSTINs
            TIS->>DB: Calculate reconciled amounts (less doorstep rejections & weight deltas)
            TIS->>DB: INSERT INTO tax_invoice (invoice_number, supplier_order_id, taxable_amount, gst_amount)
        end
        TIS-->>TIC: TaxInvoiceDetailDto
        TIC->>TIS: renderHtml(invoiceDto)
        TIC-->>Client: 200 OK (HTML/PDF Tax Invoice)
    end
```
