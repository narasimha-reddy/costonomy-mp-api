if the delivery partner is not accepting / provider unable to fullfil ? then we should requesting for a reliable provider the reliability of provider is derived from various metrics that we derive

Are there any areas in delivery module that needs to improved any gaps that we must address to for the production  launch?


Investigate and improve build and test execution time in this repository, with particular attention to safely parallelizing independent tests.

Start by inspecting the build configuration and recent build logs or CI configuration, if available. Identify the actual bottlenecks before changing settings. Preserve existing uncommitted work and avoid unrelated changes.

Goals:
- Reduce total local and CI build time without weakening test coverage or hiding failures.
- Parallelize independent tests where the build tool and tests support it.
- Keep tests that share mutable state, ports, databases, files, or other resources isolated or serialized as needed.
- Avoid making clean builds slower just to improve incremental builds, or vice versa; report the tradeoffs.

For the backend Maven modules:
- Review the reactor structure and test setup before enabling Maven parallel builds (for example, `-T`).
- Check whether modules are truly independent and whether integration tests share external resources.
- Evaluate Surefire and Failsafe fork/thread settings based on available CPU and memory; avoid unbounded concurrency.
- Check for repeated compilation, dependency resolution, and test setup that could be safely reused or cached.
- Keep unit and integration test phases and failure reporting intact.

For any frontend or other build systems in the repository:
- Inspect their existing scripts and CI workflows, then apply only relevant, safe parallelization or caching improvements.

Implementation:
1. Capture a baseline using representative clean and incremental builds, recording commands, elapsed times, and failures.
2. Make the smallest configuration or build-script changes that address measured bottlenecks.
3. Compare results against the baseline using the same commands and environment.
4. Add or update tests only if needed to validate build configuration behavior; do not skip, disable, or silently quarantine existing tests.
5. Review the diff and ensure unrelated user changes remain untouched.

Do not assume all tests are safe to run concurrently. If isolation cannot be established, keep those tests serialized and explain why.

In your final report, include:
- Bottlenecks found and changes made.
- Before/after timings, with the exact commands and environment.
- Which test groups now run concurrently and any resource limits used.
- Tests/builds executed and their results.
- Any remaining bottlenecks or concurrency risks.

Review and address the delivery module schema and recovery gaps identified in the delivery review. Re-read the current checkout before changing anything; do not assume the earlier review still matches the code.

Start with `docs/specs/06-delivery.md`, `docs/specs/02-domain-model-database.md`, `docs/specs/09-security-privacy-admin.md`, `docs/DECISIONS.md`, and `docs/performance/DB_BOTTLENECKS.md`. Inspect the current delivery migrations, repositories, jobs, services, and tests for both supplier-order deliveries and restaurant-to-customer food deliveries.

Preserve all existing work and local data. Do not drop/recreate databases, truncate delivery tables, edit already-applied migrations, or run Flyway repair to mask schema drift. The previous review found the local `costonomy_mp` database at V88 while the checkout had migrations through V93; re-check both versions before using local EXPLAIN results. Use clean Testcontainers/MySQL migration tests for the current migration chain.

Investigate these specific risks and fix only those confirmed in the current code:
- `DeliveryJobs` loads all active supplier deliveries and calls providers inside one transaction. Bound the work, keep provider calls outside database transactions, and use short per-delivery transactions without losing state-transition or retry guarantees.
- `FoodDeliveryJobs` loads every retryable or overdue food delivery. Add database-side due predicates, a bounded batch/claim strategy, and indexes that match the actual filters and ordering. Confirm the food-delivery assignment-deadline path has a suitable composite index; do not add an index based on the earlier review alone.
- `delivery_recovery_task` uses `PROCESSING` without an evident lease/reclaim path, and the due-task query may be unbounded. Design an atomic bounded claim and crash recovery for stale claims. Make deterministic tasks idempotent at the database boundary where appropriate; verify wallet-reference uniqueness before changing refund semantics. Test that concurrent workers claim a task at most once and that a process crash does not strand it forever.
- Delivery rows repeat tenant ownership identifiers from their supplier order or restaurant/outlet. Check whether the schema prevents mismatched order/outlet/store and restaurant/outlet tuples. If adding composite foreign keys, first detect and safely handle existing inconsistencies; never silently rewrite ownership. If enforcement belongs in another layer, add a focused invariant test and explain why.
- Delivery timeline queries order by provider/event time while some indexes lead with creation time. Verify with EXPLAIN on a MySQL schema at the current migration version. Add only indexes that improve a real predicate/order path, using the next free Flyway version and the repository's index naming/concurrency conventions.
- Food-delivery rows contain customer names, phone numbers, addresses and instructions; event payloads may contain additional personal data. Review the privacy/retention requirements and current purger. Do not invent a retention period or delete financial/audit evidence. If policy is unspecified, document the exact product/security decision needed and propose a retention/anonymization design before implementing destructive cleanup.

For each confirmed issue:
1. State the concrete code path, affected schema objects, and production consequence. Separate verified findings from assumptions and from items already covered by the performance baseline.
2. Make the smallest forward-only migration and code change. Never rewrite an applied migration. Check status/dirty data before adding constraints or uniqueness.
3. Add focused integration coverage against MySQL for query bounds, ownership invariants, idempotency and concurrency as applicable. Assert the resulting state and side effects, not only the response code.
4. Validate the full current migration chain on a clean database, run the focused delivery tests, and compare EXPLAIN plans before/after where an index changes. Do not reset the developer's local database as a test strategy.

Do not refactor unrelated provider flows or complete the explicitly unfinished food-delivery API/webhook work as part of this task. In the final report, rank findings by severity, name every migration/index added, list exact checks and outcomes, state any data or product decisions still needed, and identify any risk that remains unverified.


Do we have end to end functional state while carrying any activity Like earlier there is no option to move money from supplier to delivery provider Do we have other places where we have complete logical flow to carryout functionality offer by our system
 money movement across the state and  visibility observability of states


 Complete the marketplace delivery journeys so every order has a valid entry path, an observable operational state, and a financially correct exit path.

Start by inspecting the current code, migrations, tests, API responses, admin views, and delivery/payment documentation. The workspace contains uncommitted changes: preserve them and build on them. Summarize the existing journeys and identify missing transitions before editing. Then implement the fixes; do not stop at a proposal.

## 1. Map and validate every delivery journey

Cover pickup, supplier delivery, and Costonomy courier delivery from checkout through completion, cancellation, failure, refund, settlement, and reconciliation.

For each path, define and enforce:
- valid delivery and order state transitions;
- who owns the next action: customer, supplier, courier, scheduled job, or operator;
- when customer and supplier charges become due;
- what happens when a quote, booking, assignment, pickup, delivery, cancellation, or refund fails;
- the final state and next action visible to each party.

Include retries, duplicate requests and webhooks, out-of-order events, provider timeouts with unknown outcomes, supplier takeover, cancellation races, and failure after pickup. Ensure no path can leave an order appearing complete while money or delivery work is unresolved.

## 2. Complete courier payable and provider payment

Supplier deductions and direct supplier payments are collections; they do not prove the courier was paid.

Implement a durable courier payable linked to the delivery, order, provider, booking attempt, and funding sources. Track quoted cost, final provider cost, buyer contribution, supplier contribution, any platform-funded difference, currency, amount due, amount paid, payment references, timestamps, attempts, errors, and reconciliation state.

Add an idempotent provider payment lifecycle with safe claims/leases, bounded retries, ambiguous-outcome reconciliation, audit history, and operator actions for manual bank or invoice payments if providers cannot be paid through an API. Keep external calls outside database locks.

Define and document who bears differences between quoted and final courier charges, and how cancellation fees, provider credits, reassignment, failed pickup, and post-pickup failure affect the payable. Never silently change an agreed customer price or overcharge a supplier.

## 3. Match refunds to the original payment method

Audit every delivery cancellation, no-provider outcome, partial fee adjustment, and post-pickup failure.

- Wallet-funded fees must use idempotent wallet credits.
- Gateway-funded fees must use durable gateway refunds, including partial refunds.
- Refund requests must be recorded before external calls and reconciled after timeouts or missing client confirmation.
- Prevent duplicate refunds and refunds exceeding the captured or held amount.
- Ensure recovery jobs know the payment method and cannot route a gateway payment through the wallet-only refund path.

For supplier delivery-funding payments, prevent a deduction from being both collected directly and applied to a settlement. Handle concurrent confirmations, duplicate captured payments, and payment success without client confirmation. If money is captured after another path won, refund it through the durable refund workflow. Do not call the gateway from inside a database transaction or merely log a failed refund.

## 4. Make states observable and actionable

Provide role-appropriate customer, supplier, operations, and finance views that show the linked order and delivery, payer, payee, amount, currency, payment method, provider references, current state, state history, timestamps, retries, last error, next retry, and required action.

Add bounded queues for overdue, exhausted, ambiguous, and unreconciled delivery payments and refunds. Clearly distinguish:
- supplier amount pending, collected, or applied to settlement;
- courier amount due, processing, paid, or requiring reconciliation;
- customer amount captured, held, refunded, or requiring reconciliation.

A collection or settlement must never be shown as a courier payment.

## 5. Tests, migrations, and verification

Add backward-compatible migrations and tests for:
- every delivery mode’s happy path and exit paths;
- wallet and gateway fees, full and partial refunds;
- supplier deductions via settlement and direct payment;
- courier payable creation, payment, retries, ambiguity, and reconciliation;
- quote/final amount differences and cancellation fees;
- races among settlement, direct payment, cancellation, booking, and webhook processing;
- duplicate/out-of-order events and payment confirmations;
- credit orders and suppliers with no upcoming settlement;
- role-based visibility and operator actions.

Run the focused unit and integration suites, then relevant broader suites. Report commands and results. Review the final diff for missing transitions, double-debit/credit risks, orphaned money states, unsafe retries, migration issues, and journeys that have no owner or next action.

At the end, list any remaining decisions that require product or finance input, especially provider payment rails and who bears courier cost differences. Do not leave known refund or payable gaps as documentation-only follow-ups.