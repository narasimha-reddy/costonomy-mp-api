# Notifications, Events & Analytics

## 1. Domain events

Publish at minimum:

- RequirementCreated
- RequirementUpdated
- RequirementFulfilled
- ProcurementSubmitted
- ProcurementApproved
- ProcurementRejected
- SupplierOrderCreated
- SupplierOrderAccepted
- SupplierOrderPartiallyAccepted
- SupplierOrderRejected
- SupplierOrderExpired
- SupplierOrderPreparing
- SupplierOrderReady
- PaymentAuthorized
- PaymentCaptured
- PaymentFailed
- RefundCompleted
- CreditRequested
- CreditApproved
- CreditModified
- CreditReserved
- CreditUtilized
- CreditReleased
- CreditOverdue
- DeliveryRequested
- DeliveryProviderSelected
- DriverAssigned
- DeliveryPickedUp
- DeliveryLocationUpdated
- DeliveryReassigned
- DeliveryDelivered
- ReceivingCompleted
- DisputeCreated
- DisputeResolved
- RatingSubmitted
- SettlementGenerated
- SettlementPaid

## 2. Event contract

Every event should include:

```json
{
  "eventId": "uuid",
  "eventType": "SupplierOrderAccepted",
  "occurredAt": "2026-09-14T10:00:00Z",
  "aggregateType": "SUPPLIER_ORDER",
  "aggregateId": "123",
  "actorId": "456",
  "correlationId": "uuid",
  "payloadVersion": 1,
  "payload": {}
}
```

## 3. Outbox

Events must be persisted transactionally with the state change.

Publisher retries.

Consumers are idempotent.

## 4. Notifications

Channels:

- push
- in-app
- SMS for selected critical events
- future email/WhatsApp where configured

Critical notifications:

- supplier acceptance/rejection/expiry
- payment failure
- delivery assignment/failure/delivery
- credit approval/modification/overdue
- approval requests
- dispute updates

## 5. Notification preferences

Users can configure non-critical notification preferences.

Critical operational/financial notifications may be mandatory according to policy.

## 6. Notification states

`CREATED → QUEUED → SENT → DELIVERED`

Failure:

`FAILED`

Retry with bounded backoff.

## 7. Analytics events

Recommended analytics:

### Discovery

- search_started
- search_submitted
- search_result_viewed
- product_viewed
- supplier_viewed
- recommendation_viewed

### Procurement

- requirement_created
- recommendation_selected
- cart_created
- checkout_started
- checkout_validation_failed
- checkout_submitted

### Supplier

- supplier_order_viewed
- supplier_order_accepted
- supplier_order_partial_accepted
- supplier_order_rejected
- supplier_order_expired

### Payment

- payment_started
- payment_authorized
- payment_failed
- payment_captured
- refund_started
- refund_completed

### Delivery

- delivery_requested
- provider_selected
- driver_assigned
- tracking_viewed
- delivery_reassigned
- delivery_delivered

### Post-order

- receiving_started
- receiving_completed
- dispute_created
- rating_submitted

## 8. Analytics privacy

Never log:

- OTP
- card number
- CVV
- payment secrets
- provider authentication secrets

Use internal IDs instead of unnecessary personal data.

## 9. Funnel metrics

Daily funnel:

`Requirement → Sourcing → Procurement → Supplier Acceptance → Fulfillment → Delivery → Receive → Repeat`

Track drop-off by:

- restaurant
- outlet
- category
- supplier
- city
- payment method
- delivery mode

## 10. North star

Successfully fulfilled GMV through Mandi.

## 11. Operational dashboards

Backend should expose data for:

- active orders
- supplier acceptance SLA
- fill rate
- delivery failure
- credit overdue
- payment reconciliation
- dispute volume
- settlement status

Operations web UI is future scope; APIs are required now.
