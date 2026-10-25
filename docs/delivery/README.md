# Delivery documentation map

Start here. Each document has one job; where two disagree, the order of authority is **code and migrations →
`CURRENT_STATE.md` / `DIAGRAMS.md` → `DECISIONS.md` (latest decision wins) → specs → everything else**.

| Document | Job | Trust it for |
|---|---|---|
| [CURRENT_STATE.md](CURRENT_STATE.md) | What exists, per journey, with implemented / tested / deployed / app-visible evidence; duplicated triggers; known gaps | "Does X work, is it tested, can a user see it?" |
| [DIAGRAMS.md](DIAGRAMS.md) | Product journey, state machines, money movement, production architecture | Shapes and states |
| [DECISION_INDEX.md](DECISION_INDEX.md) | Decision → feature → diagram → spec → API → tests | Finding the reasoning |
| [../DECISIONS.md](../DECISIONS.md) | Detailed historical decision log (append-only; D-207 is the latest) | Why |
| [../specs/06-delivery.md](../specs/06-delivery.md) | Delivery specification | Contracts and rules |
| [../specs/IMPLEMENTATION_TRACEABILITY.md](../specs/IMPLEMENTATION_TRACEABILITY.md) | Requirement → code/DB/API/tests rows, plus the delivery evidence table | Status of each requirement |
| [../architecture/sequence-diagrams/02-delivery-and-logistics.md](../architecture/sequence-diagrams/02-delivery-and-logistics.md) | Message-level sequences | Who calls whom (check the status banner) |
| [../architecture/delivery-system-implementation-plan.md](../architecture/delivery-system-implementation-plan.md) | The original design brief, with a status table added | History and intent, **not** current design |
| [delivery_module_guide.md](delivery_module_guide.md) | Product-team walkthrough (corrected) | Orientation only |
| [delivery_interactive_visualizer.html](delivery_interactive_visualizer.html) | Interactive product and money-movement walkthrough (supplier B2B & food B2C, waterfall, courier payables, ops resolution) | Walkthrough and demos; **not** a source of truth |
| [prompts.md](prompts.md) | Log of the prompts used to drive the delivery work | Nothing — it is not documentation |

## Conventions used across these documents

- **Implemented / Tested / Deployed / App-visible** are four separate claims. "Tested" means a named test exists, not that
  it passed. "Deployed" is `Unknown` unless a deploy artifact or release record names an environment; none exists today.
- A diagram or table is labelled **conceptual**, **planned**, **sandbox-only**, **disabled by default** or **not built** when it is;
  "production" is never used for something without deployment evidence.
- Code citations are `path:line` against the working tree of 2026-10-25 and drift; the class and method names are the stable part.
- Statuses and names in these documents were checked against the repository: `DeliveryStatus` (15 values),
  `FoodDeliveryStatus` (14), `CourierPayableState` (8), migrations V1–V105.
