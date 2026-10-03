# Production-Grade Multi-Provider Delivery Orchestration Platform

## 1. Objective

Design and implement a **production-grade Delivery Orchestration & Smart Dispatch platform** for a restaurant and marketplace ecosystem.

The platform must provide a **single unified delivery API** to the rest of the marketplace while abstracting multiple delivery providers such as:

* Pidge
* Shadowfax
* Porter
* Borzo
* LoadShare
* Rapido
* Internal/owned delivery fleet (1PL)

The core objective is to ensure that a restaurant/customer order is dispatched reliably with the **right delivery partner, vehicle type, SLA, and cost**, while minimizing unassigned orders and manual intervention.

The system must be:

* Provider-agnostic
* Highly reliable
* Idempotent
* Observable
* Fault tolerant
* Horizontally scalable
* Easy to extend with new providers
* Easy to reason about during failures
* Production-grade rather than a POC

Do not tightly couple business logic to any individual courier provider.

---

# 2. Core Business Concept

The marketplace should expose only one internal API:

```http
POST /delivery/create
```

The marketplace should NOT need to know whether the delivery is ultimately fulfilled by:

* Pidge
* Shadowfax
* Porter
* Borzo
* LoadShare
* Rapido
* 1PL/internal riders

The orchestration layer decides this.

Conceptually:

```text
Restaurant / Marketplace
        |
        v
POST /delivery/create
        |
        v
Delivery Orchestration Service
        |
        +---- Provider Adapter: Pidge
        |
        +---- Provider Adapter: Shadowfax
        |
        +---- Provider Adapter: Porter
        |
        +---- Provider Adapter: Borzo
        |
        +---- Provider Adapter: LoadShare
        |
        +---- Provider Adapter: Rapido
        |
        +---- Internal Fleet
```

---

# 3. Important Architectural Principle

Separate the system into three distinct concerns:

### A. Delivery Domain

Owns business concepts such as:

* Delivery
* Delivery attempt
* Dispatch policy
* Vehicle requirement
* SLA
* Delivery state
* Provider selection
* Fallback policy

### B. Orchestration Engine

Responsible for:

* Selecting providers
* Requesting quotes
* Booking delivery
* Monitoring assignment
* Applying timeout policies
* Cancelling failed attempts
* Cascading to fallback providers
* Handling retries
* Handling provider failures

### C. Provider Adapters

Each provider must implement a common interface.

For example:

```java
interface DeliveryProvider {

    QuoteResponse getQuote(DeliveryRequest request);

    BookingResponse createDelivery(DeliveryRequest request);

    DeliveryStatus getStatus(String providerDeliveryId);

    CancellationResponse cancelDelivery(String providerDeliveryId);

    TrackingResponse getTracking(String providerDeliveryId);
}
```

Do not allow provider-specific APIs to leak into the domain layer.

---

# 4. Pidge Integration

Pidge should initially be treated as one of the delivery providers rather than the core business domain.

Pidge provides capabilities such as:

* Order creation
* Smart/manual allocation
* Courier allocation
* Webhook callbacks
* Delivery status updates
* Tracking URLs
* Rider assignment
* Proof of delivery
* Internal fleet routing/optimization

Relevant conceptual API operations include:

```text
Create Order
Get Delivery Status
Cancel Delivery
Get Tracking
Webhook Events
```

Pidge credentials such as:

```text
API Token
API Secret
Webhook Secret
```

must never be hardcoded.

Use:

```text
Secret Manager / Vault / Environment Configuration
```

and support credential rotation.

Before implementing provider-specific behavior, validate the latest Pidge API contract against its official documentation/Postman collection. Do not assume undocumented request/response fields.

---

# 5. Unified Delivery API

Expose a provider-independent API:

```http
POST /delivery/create
```

Example conceptual request:

```json
{
  "orderId": "ORD-12345",
  "pickup": {
    "name": "Restaurant A",
    "address": "...",
    "latitude": 17.45,
    "longitude": 78.38,
    "contactNumber": "..."
  },
  "drop": {
    "name": "Customer",
    "address": "...",
    "latitude": 17.44,
    "longitude": 78.40,
    "contactNumber": "..."
  },
  "package": {
    "weightGrams": 850,
    "volume": 2.5,
    "category": "FOOD"
  },
  "payment": {
    "mode": "PREPAID",
    "codAmount": 0
  },
  "deliveryRequirements": {
    "vehicleType": "TWO_WHEELER",
    "maxPickupWaitMinutes": 15
  }
}
```

The response should NOT expose provider-specific implementation details.

Example:

```json
{
  "deliveryId": "DEL-12345",
  "status": "DISPATCHING",
  "trackingUrl": null
}
```

---

# 6. Delivery Lifecycle

Design an explicit state machine.

Suggested states:

```text
CREATED
  |
  v
QUOTING
  |
  v
DISPATCHING
  |
  v
ASSIGNMENT_PENDING
  |
  +---- ASSIGNED
  |       |
  |       v
  |    PICKED_UP
  |       |
  |       v
  |   OUT_FOR_DELIVERY
  |       |
  |       v
  |    DELIVERED
  |
  +---- FAILED
          |
          v
       FALLBACK
```

Additional states may be introduced if required.

Do not allow arbitrary state transitions.

Define:

```text
allowed transitions
terminal states
retryable states
failure states
provider-specific states
```

Maintain a clear mapping between provider states and canonical platform states.

---

# 7. Integration With Restaurant Order Lifecycle

Delivery dispatch must be tied to the order lifecycle.

Example:

```text
ORDER_CREATED
      |
      v
RESTAURANT_ACCEPTED
      |
      v
FOOD_READY_ESTIMATE = 15 MIN
      |
      v
QUOTE / DISPATCH PREPARATION
      |
      v
ORDER_PREPARING
      |
      v
RESERVE / BOOK CARRIER
      |
      v
ORDER_PACKED
      |
      v
DRIVER ARRIVAL / PICKUP
      |
      v
OUT_FOR_DELIVERY
      |
      v
DELIVERED
```

The system should avoid dispatching too early because the rider may arrive before food is ready.

Use the restaurant's preparation estimate to determine dispatch timing.

For example:

```text
Food ready in 15 minutes
+
Provider expected assignment/pickup time
=
Optimal dispatch trigger
```

The exact algorithm should be configurable.

---

# 8. Quote and Provider Selection

The system should support two different strategies:

### Strategy A — Quote First

Request quotes from eligible providers:

```text
Shadowfax
Porter
Borzo
LoadShare
Rapido
```

Then select based on configurable rules.

Example:

```text
lowest cost
fastest ETA
best SLA
provider priority
historical reliability
vehicle compatibility
geographical availability
```

### Strategy B — Provider Priority

Example:

```text
1. Shadowfax
2. Borzo
3. Rapido
4. LoadShare
```

The system attempts providers according to priority.

The strategy must be configurable rather than hardcoded.

---

# 9. Waterfall Dispatch

The system must support waterfall dispatch to minimize unassigned orders.

Example:

```text
Attempt Shadowfax
       |
       | assignment received
       v
    SUCCESS

       |
       | timeout / failure
       v

Cancel Shadowfax attempt
       |
       v
Attempt Borzo
       |
       | timeout / failure
       v
Attempt Rapido
       |
       | timeout / failure
       v
Attempt LoadShare
       |
       | failure
       v
Escalate / Manual Intervention
```

Example timeout:

```text
Provider assignment timeout = 3–5 minutes
```

However, this must be configurable by:

* Provider
* City
* Vehicle type
* Delivery type
* Time of day
* SLA
* Business priority

---

# 10. Important Waterfall Safety Rule

Never blindly create multiple active bookings for the same delivery.

The orchestration engine must maintain:

```text
deliveryId
attemptId
provider
providerDeliveryId
attemptStatus
```

Before starting the next attempt:

```text
1. Confirm previous attempt is cancelled/failed
2. Confirm provider state if possible
3. Prevent duplicate active bookings
4. Acquire distributed lock/idempotency protection
5. Start next attempt
```

The system must protect against race conditions such as:

```text
Shadowfax assigns rider
        +
timeout process starts Borzo
        =
two riders assigned
```

This scenario must be explicitly handled.

---

# 11. Vehicle Selection

Vehicle selection should be based on package characteristics.

Example:

```text
Food / small parcel
        |
        v
2-wheeler
        |
        +-- Shadowfax
        +-- Borzo
        +-- Rapido
        +-- LoadShare
```

For large catering/bulk orders:

```text
Large package
        |
        v
3-wheeler / Eeco / Tata Ace
        |
        +-- Porter
```

Do NOT hardcode simplistic weight thresholds.

Create a configurable vehicle-capability model:

```text
VehicleType
Capacity
WeightLimit
VolumeLimit
ProviderSupport
GeographicalAvailability
```

Example:

```text
TWO_WHEELER
THREE_WHEELER
FOUR_WHEELER
LCV
```

The routing decision should consider:

```text
weight
volume
package dimensions
number of packages
distance
provider capabilities
vehicle availability
delivery category
```

---

# 12. Failure Handling

Failure handling is one of the most important parts of this system.

Design failures using a structured taxonomy.

## Category 1 — Validation Failure

Examples:

```text
Invalid coordinates
Invalid phone number
Missing pickup
Missing drop
Invalid package weight
Unsupported vehicle
```

Action:

```text
Reject request immediately
Do not call provider
Return meaningful error
```

---

## Category 2 — Provider Technical Failure

Examples:

```text
HTTP 500
HTTP 502
HTTP 503
Timeout
Connection failure
DNS failure
Malformed provider response
```

Action:

```text
Classify as transient/non-transient
Retry only where safe
Use exponential backoff
Apply retry limits
Move to next provider when appropriate
```

---

## Category 3 — Provider Business Failure

Examples:

```text
NO_RIDER_AVAILABLE
SERVICE_AREA_NOT_SUPPORTED
VEHICLE_NOT_AVAILABLE
CREDIT_LIMIT_EXCEEDED
INVALID_ADDRESS
ORDER_REJECTED
```

Action:

```text
Do not blindly retry
Mark attempt appropriately
Apply fallback policy
```

---

## Category 4 — Assignment Timeout

Example:

```text
Booking accepted by provider
but rider not assigned within 5 minutes
```

Action:

```text
Mark attempt ASSIGNMENT_TIMEOUT
Cancel provider request
Verify cancellation
Start next provider
```

---

## Category 5 — Cancellation Failure

Potential scenario:

```text
Provider A timeout
Cancellation request sent
Cancellation response unavailable
```

Do NOT immediately assume cancellation succeeded.

Use:

```text
CANCEL_REQUESTED
        |
        v
VERIFY_PROVIDER_STATE
        |
        +---- CANCELLED
        |
        +---- RIDER_ASSIGNED
        |
        +---- UNKNOWN
```

If state is UNKNOWN, the orchestration engine must prevent unsafe duplicate bookings and escalate according to policy.

---

# 13. Webhook Processing

Providers will send asynchronous events.

Example:

```text
ORDER_CREATED
RIDER_ASSIGNED
REACHED_PICKUP
PICKED_UP
OUT_FOR_DELIVERY
DELIVERED
CANCELLED
FAILED
```

Create a canonical event model:

```text
DeliveryEvent
```

Example:

```json
{
  "deliveryId": "DEL-123",
  "provider": "PIDGE",
  "providerDeliveryId": "PD-123",
  "eventType": "RIDER_ASSIGNED",
  "eventTime": "...",
  "metadata": {}
}
```

Provider events must be mapped into canonical events.

---

# 14. Webhook Reliability

Webhook processing must be:

* Idempotent
* Authenticated
* Durable
* Replay-safe
* Observable

Do not process webhook business logic directly inside the HTTP request thread.

Recommended pattern:

```text
Provider
   |
   v
Webhook API
   |
   v
Validate signature
   |
   v
Persist event
   |
   v
Publish internal event
   |
   v
Async Consumer
   |
   v
Update Delivery State
```

Use an inbox/event-deduplication mechanism.

Duplicate events must not create duplicate state transitions.

---

# 15. Out-of-Order Events

Explicitly handle:

```text
DELIVERED
arrives before
OUT_FOR_DELIVERY
```

or:

```text
RIDER_ASSIGNED
arrives twice
```

The state machine must determine whether an event is:

```text
valid
duplicate
late
out-of-order
invalid
```

Never blindly update the database based solely on event arrival order.

---

# 16. Tracking

The platform should expose:

```http
GET /delivery/{deliveryId}
```

and optionally:

```http
GET /delivery/{deliveryId}/tracking
```

Return:

```json
{
  "deliveryId": "DEL-123",
  "status": "OUT_FOR_DELIVERY",
  "rider": {
    "name": "...",
    "phone": "..."
  },
  "trackingUrl": "...",
  "estimatedArrival": "..."
}
```

The marketplace can use this to send WhatsApp/SMS notifications.

---

# 17. Customer Notifications

Delivery events should generate notification events.

Example:

```text
RIDER_ASSIGNED
        |
        v
Customer Notification

OUT_FOR_DELIVERY
        |
        v
WhatsApp/SMS

DELIVERED
        |
        v
Delivery Confirmation
```

Notification delivery should be asynchronous.

Do not make courier/provider API calls dependent on WhatsApp/SMS success.

---

# 18. Central Wallet / Billing

The platform should abstract provider billing.

Conceptually:

```text
Marketplace
      |
      v
Central Delivery Wallet
      |
      +---- Shadowfax
      +---- Porter
      +---- Borzo
      +---- LoadShare
```

Maintain an internal ledger.

Do NOT rely only on provider balance APIs.

Track:

```text
deliveryId
provider
providerTransactionId
quotedAmount
finalAmount
walletDebit
refund
adjustment
timestamp
```

The ledger must be auditable.

Financial operations should be idempotent.

---

# 19. Data Model

Design a production-grade persistence model.

At minimum consider:

```text
Delivery
DeliveryAttempt
Provider
ProviderConfiguration
Quote
DeliveryEvent
WebhookEvent
TrackingInformation
DispatchPolicy
VehicleCapability
WalletTransaction
Notification
```

Important identifiers:

```text
deliveryId
orderId
attemptId
provider
providerDeliveryId
idempotencyKey
```

Explain which identifiers are generated by our platform versus providers.

---

# 20. Idempotency

Every externally triggered operation must be idempotent.

For:

```http
POST /delivery/create
```

support:

```http
Idempotency-Key
```

Example:

```text
same order
same idempotency key
multiple requests
        |
        v
one delivery
```

Prevent duplicate courier bookings.

Also apply idempotency to:

```text
create booking
cancel booking
wallet debit
webhook processing
notification generation
```

---

# 21. Concurrency

Explicitly design for concurrent operations.

Example race:

```text
Thread A:
Provider timeout detected

Thread B:
Provider webhook says RIDER_ASSIGNED
```

The system must define which state transition wins based on authoritative provider state and business rules.

Use appropriate mechanisms such as:

```text
optimistic locking
database versioning
distributed locks
compare-and-set updates
transactional outbox
```

Do not use distributed locking everywhere by default. Explain why each concurrency mechanism is required.

---

# 22. Retry Strategy

Define retries separately for:

```text
Provider API
Webhook processing
Message consumption
Database operations
Notifications
```

Use:

```text
exponential backoff
jitter
maximum attempts
dead-letter queues
```

Do not retry business failures indefinitely.

Clearly classify:

```text
RETRYABLE
NON_RETRYABLE
FALLBACK
MANUAL_INTERVENTION
```

---

# 23. Reliability Requirements

The system should continue functioning when:

```text
one provider is unavailable
provider API is slow
provider API returns errors
webhook delivery fails
webhook arrives multiple times
webhook arrives late
message broker temporarily fails
notification service fails
database transaction fails
provider cancellation is uncertain
```

Design graceful degradation.

---

# 24. Observability

Implement:

### Metrics

```text
delivery_success_rate
delivery_failure_rate
provider_success_rate
provider_assignment_time
provider_timeout_rate
fallback_rate
average_delivery_cost
quote_to_booking_conversion
webhook_processing_latency
notification_success_rate
```

### Logs

Use structured logs.

Every log should include relevant correlation identifiers:

```text
traceId
orderId
deliveryId
attemptId
provider
providerDeliveryId
```

Never log:

```text
API secrets
tokens
customer sensitive information
payment credentials
```

---

# 25. Distributed Tracing

Trace the complete lifecycle:

```text
Order Service
   |
   v
Delivery Service
   |
   v
Provider Adapter
   |
   v
Provider API
   |
   v
Webhook
   |
   v
Delivery Event Processor
   |
   v
Notification Service
```

Use OpenTelemetry-compatible tracing.

---

# 26. Architecture

Prefer a modular architecture such as:

```text
delivery-domain
delivery-application
delivery-orchestration
delivery-provider-spi
delivery-provider-pidge
delivery-provider-shadowfax
delivery-provider-porter
delivery-provider-borzo
delivery-provider-loadshare
delivery-provider-rapido
delivery-infrastructure
delivery-api
```

Use Hexagonal Architecture / Ports and Adapters where appropriate.

The domain must not depend on:

```text
HTTP clients
Kafka
database
Pidge SDK
provider-specific DTOs
```

---

# 27. Provider Adapter Pattern

Define a provider SPI.

Example:

```java
interface DeliveryProvider {

    ProviderType provider();

    QuoteResult quote(DeliveryContext context);

    BookingResult book(DeliveryContext context);

    CancellationResult cancel(CancellationContext context);

    ProviderDeliveryStatus getStatus(StatusContext context);

    TrackingResult getTracking(TrackingContext context);
}
```

Each provider implementation translates:

```text
Canonical Domain Model
        |
        v
Provider Request
        |
        v
Provider API
        |
        v
Provider Response
        |
        v
Canonical Domain Model
```

Never expose provider DTOs outside the adapter.

---

# 28. Configuration-Driven Dispatch

Avoid code changes for operational policy changes.

Configuration should control:

```text
provider priority
timeout
retry count
vehicle compatibility
city availability
maximum package weight
maximum package volume
cost thresholds
SLA thresholds
fallback sequence
```

Example:

```yaml
dispatch:
  food:
    twoWheeler:
      providers:
        - shadowfax
        - borzo
        - rapido
        - loadshare

  bulk:
    providers:
        - porter
        - loadshare
```

The actual implementation should use a proper configuration model rather than assuming YAML is the final design.

---

# 29. Multi-City / Future Expansion

Design for future expansion.

The same architecture should support:

```text
Hyderabad
Bangalore
Chennai
Mumbai
Delhi
```

Provider availability can vary by city.

Therefore:

```text
ProviderSelectionContext
```

should include:

```text
city
zone
pickup location
drop location
vehicle type
delivery category
time
SLA
```

---

# 30. Security

The integration must include:

```text
OAuth/API credentials where applicable
secret management
webhook signature verification
request authentication
authorization
rate limiting
PII protection
audit logging
```

Never expose provider credentials through APIs.

---

# 31. Database Consistency

Clearly identify transactional boundaries.

For example:

```text
Create Delivery
+
Create Delivery Attempt
+
Create Outbox Event
```

should be considered carefully for atomicity.

Use the transactional outbox pattern where appropriate.

Avoid distributed transactions with external courier providers.

---

# 32. External API Boundary

Never keep a database transaction open while waiting for:

```text
Pidge
Shadowfax
Porter
Borzo
LoadShare
Rapido
```

External provider calls must be treated as distributed-system operations.

---

# 33. Failure Decision Matrix

Create an explicit failure matrix.

Example:

| Failure              |   Retry |    Fallback |  Cancel | Manual |
| -------------------- | ------: | ----------: | ------: | -----: |
| Invalid address      |      No |       Maybe |     Yes |  Maybe |
| Provider 500         |     Yes |         Yes | Depends |     No |
| Provider timeout     | Limited |         Yes |     Yes |     No |
| No rider available   |      No |         Yes |     Yes |     No |
| Assignment timeout   |      No |         Yes |     Yes |     No |
| Cancellation unknown |      No | Conditional |  Verify |    Yes |
| Duplicate webhook    |      No |          No |      No |     No |
| Delivered event      |      No |          No |      No |     No |
| Wallet failure       | Limited |          No |      No |    Yes |
| Database outage      |     Yes |          No |      No |     No |

Do not blindly use this table as implementation truth; refine it based on actual provider contracts and business requirements.

---

# 34. Dead-Letter Handling

Events that cannot be processed after configured retries should go to:

```text
DLQ
```

Examples:

```text
invalid webhook
unknown delivery
invalid state transition
provider mapping failure
persistent database failure
```

Provide operational tooling to:

```text
inspect
replay
discard
reprocess
```

DLQ messages must preserve enough context for debugging.

---

# 35. Admin / Operational Requirements

Design APIs or internal tooling to answer:

```text
Why is this delivery not assigned?
Which provider was attempted?
Why was provider X skipped?
How long did each attempt take?
Why did fallback occur?
Which provider finally accepted?
How much did it cost?
Was cancellation successful?
Was the customer notified?
```

A delivery should be explainable end-to-end.

---

# 36. Audit Trail

Maintain an immutable delivery timeline:

```text
10:00 Delivery Created
10:01 Quotes Requested
10:02 Shadowfax Booking Created
10:06 Shadowfax Assignment Timeout
10:06 Shadowfax Cancellation Requested
10:07 Cancellation Confirmed
10:07 Borzo Booking Created
10:08 Rider Assigned
10:25 Picked Up
10:48 Out For Delivery
11:10 Delivered
```

This should be queryable for customer support and operations.

---

# 37. Implementation Approach

Implement incrementally.

## Phase 1 — Domain Foundation

Build:

```text
Delivery domain
State machine
DeliveryAttempt
Provider SPI
Idempotency
Persistence model
```

No real provider integration initially.

Use mock providers.

---

## Phase 2 — Pidge

Implement:

```text
Pidge adapter
Create delivery
Cancellation
Status
Tracking
Webhook
Signature validation
```

Test against sandbox/mock environment where available.

---

## Phase 3 — Dispatch Engine

Implement:

```text
Provider selection
Quote comparison
Provider priority
Timeout
Fallback
Retry
Concurrency protection
```

---

## Phase 4 — Additional Providers

Add:

```text
Shadowfax
Porter
Borzo
LoadShare
Rapido
```

Each provider should require only a new adapter and configuration.

Avoid modifying the domain model for every new provider.

**Borzo — added 2026-10-01, see D-113.** `delivery.provider.borzo`: `BorzoApiClient`,
`BorzoDeliveryProvider`, `BorzoStatusMapper`, `BorzoContractException`, gated by
its own `costonomy.mp.borzo.enabled` flag (independent of Pidge's single-valued
`costonomy.mp.providers.delivery` switch) plus a `delivery_provider` row (V49,
seeded disabled). One port-level exception to "only a new adapter and
configuration": `DeliveryProvider.QuoteRequest` gained `pickupAddress`/
`dropAddress`, because Borzo's `calculate-order` requires address text per
point and nothing upstream of it carried that. Added as a new constructor
overload — Pidge and the mocks, which never read the field, are unaffected.
Borzo's webhook (`BorzoWebhookService`, the `/borzo` route) is not implemented;
the payload/signature contract is unverified and this phase explicitly
required testing against the sandbox first, which status polling (`GET
/orders`) satisfies in the meantime.

---

## Phase 5 — Notifications

Implement:

```text
WhatsApp
SMS
Tracking URL
Delivery status notifications
```

---

## Phase 6 — Wallet & Billing

Implement:

```text
wallet
ledger
provider transactions
reconciliation
refunds
adjustments
```

---

## Phase 7 — Production Hardening

Implement:

```text
observability
alerts
DLQ
replay
rate limiting
security
load testing
chaos testing
failure testing
operational dashboards
```

---

# 38. Testing Strategy

Do not limit testing to happy paths.

Create tests for:

### Provider failures

```text
500
502
503
timeout
connection reset
malformed response
```

### Dispatch failures

```text
no rider
assignment timeout
late assignment
duplicate assignment
provider cancellation failure
```

### Webhooks

```text
duplicate
out-of-order
late
invalid signature
unknown delivery
```

### Concurrency

Test:

```text
timeout + rider assigned simultaneously
duplicate create requests
duplicate cancellation
multiple webhook consumers
```

### Recovery

Test:

```text
service restart
database restart
message broker restart
provider recovery
reprocessing DLQ
```

---

# 39. Key Design Questions the Agent Must Answer

Before writing production code, explicitly answer:

1. What is the source of truth for delivery state?
2. How do we prevent duplicate bookings?
3. How do we handle timeout vs late provider assignment races?
4. How do we guarantee webhook idempotency?
5. How do we handle out-of-order events?
6. How do we determine when to start dispatch relative to food readiness?
7. How do we choose between cheapest and fastest provider?
8. How do we configure provider waterfall rules?
9. How do we safely cancel an old provider before starting another?
10. What happens if cancellation status is unknown?
11. What happens if every provider fails?
12. How do we handle provider API downtime?
13. How do we reconcile provider billing with our internal wallet?
14. How do we recover from failed asynchronous events?
15. How can operations explain exactly why a delivery failed?
16. How do we onboard a new provider without modifying core business logic?
17. How do we handle city-specific provider availability?
18. How do we ensure PII and provider credentials are protected?
19. What metrics indicate that the dispatch engine is degrading?
20. Which operations require strong consistency and which can be eventually consistent?

---

# 40. Expected Agent Output

Do NOT immediately start generating code.

First produce the following artifacts in order:

### Step 1 — Architecture

Provide:

```text
High-level architecture
Component diagram
Service boundaries
Data flow
Provider integration flow
```

### Step 2 — Domain Model

Provide:

```text
entities
value objects
aggregates
state machines
domain events
```

### Step 3 — API Contract

Define:

```text
POST /delivery/create
GET /delivery/{id}
GET /delivery/{id}/tracking
POST /delivery/{id}/cancel
```

Include:

```text
request
response
errors
idempotency
authentication
```

### Step 4 — Provider SPI

Define the common provider interface and explain why each method exists.

### Step 5 — Dispatch Algorithm

Explain:

```text
quote
provider selection
booking
timeout
cancellation
fallback
retry
```

using sequence diagrams.

### Step 6 — Failure Model

Create a comprehensive failure taxonomy and decision matrix.

### Step 7 — Persistence

Provide:

```text
ER diagram
tables
indexes
constraints
optimistic locking strategy
```

### Step 8 — Event Architecture

Define:

```text
events
topics
outbox
inbox
DLQ
idempotency
```

### Step 9 — Provider Implementation

Implement Pidge first.

Then show how the exact same SPI can support:

```text
Shadowfax
Porter
Borzo
LoadShare
Rapido
```

### Step 10 — Production Readiness

Review:

```text
security
scalability
observability
resilience
performance
failure recovery
cost
operational support
```

---

# 41. Coding Rules for the Agent

When generating implementation:

* Prefer simple, explicit code over clever abstractions.
* Keep provider-specific code inside provider adapters.
* Do not leak provider DTOs into domain/application layers.
* Do not introduce unnecessary microservices.
* Explain every major abstraction.
* Use strong typing.
* Use immutable domain objects where appropriate.
* Validate all external input.
* Make external operations idempotent.
* Never assume external APIs are reliable.
* Never assume webhooks arrive exactly once or in order.
* Never hold database transactions open during external API calls.
* Include structured logging.
* Include correlation IDs.
* Include metrics and tracing hooks.
* Include unit tests.
* Include integration tests.
* Include failure-path tests.
* Include concurrency tests.
* Include API contract tests for provider adapters.

If a requirement is ambiguous, identify the ambiguity and propose explicit alternatives rather than silently choosing an assumption.

---

# 42. Final Design Goal

The resulting platform should make the following possible:

```text
                    MARKETPLACE
                         |
                         v
                POST /delivery/create
                         |
                         v
             DELIVERY ORCHESTRATOR
                         |
             +-----------+-----------+
             |           |           |
             v           v           v
          QUOTING     POLICY      CAPABILITY
             |           |           |
             +-----------+-----------+
                         |
                         v
                PROVIDER ROUTER
                         |
       +---------+-------+-------+---------+
       |         |       |       |         |
       v         v       v       v         v
    Pidge   Shadowfax Porter  Borzo   LoadShare
       |         |       |       |         |
       +---------+-------+-------+---------+
                         |
                         v
                  DELIVERY ATTEMPT
                         |
             +-----------+-----------+
             |                       |
          SUCCESS                  FAILURE
             |                       |
             v                       v
         TRACKING                 FALLBACK
             |                       |
             v                       v
       CUSTOMER UPDATE          NEXT PROVIDER
```

The architecture should make **provider replacement, provider addition, fallback, failure recovery, and operational debugging first-class capabilities**, rather than features added later.

The final solution must be explainable, testable, observable, and production-ready.
