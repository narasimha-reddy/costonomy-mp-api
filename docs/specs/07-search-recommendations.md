# Search, Matching & Recommendations

## 1. Objective

Make Mandi product-first and recommendation-led.

The system should answer:

> Given this restaurant requirement, which available supplier offer provides the best overall value?

## 2. Canonical product search

Search against canonical products and aliases.

Indexable fields:

- name
- normalized name
- aliases
- category
- brand where useful
- pack/unit
- status

Search must tolerate:

- spelling variations
- common restaurant terminology
- case differences
- whitespace
- common abbreviations

Do not invent semantic mappings without a canonical alias/configuration.

## 3. Supplier offer matching

For each requirement item:

1. resolve canonical product
2. find active supplier SKUs
3. filter unavailable SKUs
4. filter supplier stores that are inactive/offline
5. filter serviceability
6. calculate commercial value
7. estimate ETA
8. score trust/performance
9. rank

## 4. Best Value ranking

Initial conceptual scoring:

`Score = price + availability + ETA + fill rate + on-time + quality + rating + cancellation/reliability`

The actual implementation must normalize each component and keep weights/configuration versioned.

Commission is never a positive or negative ranking factor.

Sponsored placement, if introduced, must be separate and clearly labeled.

## 5. Explainability

Recommendation response should contain explanation codes, for example:

- `BEST_TOTAL_VALUE`
- `FASTEST_AVAILABLE`
- `HIGH_FILL_RATE`
- `RELIABLE_SUPPLIER`
- `LOWER_HISTORICAL_COST`

Do not display an explanation that is not supported by actual calculated data.

## 6. New suppliers

New suppliers may receive limited exposure until enough performance history exists.

Do not penalize new suppliers indefinitely.

Use cold-start policy/configuration.

## 7. Requirement sourcing

For multi-item requirement:

1. evaluate single-supplier fulfillment
2. evaluate multi-supplier splits
3. preserve delivery/transaction complexity
4. rank alternatives by total value

Do not automatically split if the result is materially worse without explaining the tradeoff.

## 8. Recommendation output

Each candidate should include:

- supplier
- supplier store
- canonical product
- supplier SKU
- brand
- pack
- available quantity
- unit price
- GST
- delivery fee estimate
- ETA
- total estimated cost
- score
- explanation
- historical/reliability indicators where available

## 9. Search suggestions

Suggestions may use:

- popular canonical products
- recent restaurant searches
- recent purchases
- open requirements
- category shortcuts

User-specific suggestions must be tenant-scoped.

## 10. Buy Again

Buy Again is derived from completed historical orders.

It must not blindly reuse stale price/availability.

On selection:

- retrieve current offer
- validate current availability
- show current price
- require normal checkout validation

## 11. Recommended Procurement

Recommendations may originate from:

- open requirement
- historical purchase pattern
- configured restaurant demand
- future Costonomy consumption intelligence

Initial implementation may use explicit requirement and transaction history.

## 12. Search architecture

Search infrastructure can be introduced for performance, but:

- MySQL remains transactional authority
- index is rebuildable
- indexing is asynchronous
- failed indexing is retryable
- stale search results are revalidated before checkout

Do not make OpenSearch/Redis authoritative.

## 13. Serviceability

Supplier store serviceability is based on configured:

- geography
- delivery radius
- delivery policy
- timings
- operational status

Final order validation must re-check serviceability.

## 14. Recommendation tests

Cover:

- cheapest is not recommended when reliability is materially worse
- unavailable supplier excluded
- expired offer excluded
- offline store excluded
- new supplier cold start
- equal offers deterministic tie-break
- multi-supplier requirement
- price changed between recommendation and checkout
- provider delivery fee changes
