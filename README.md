# costonomy-mp-api

Spring Boot backend for **Mandi**, Costonomy's restaurant procurement
marketplace. Modular monolith on MySQL `costonomy_mp`.

## Where to look

| | |
|---|---|
| `CLAUDE.md` | how to work in this repo, and the rules that are not negotiable |
| `docs/specs/` | the full specification set |
| `docs/DECISIONS.md` | decisions the specs don't settle, plus **open questions** |

Mobile client: `costonomy-mp-mobile`.

## Status

Specification only — no application code yet. The build sequence is in
`docs/specs/00-README.md` §8; next up is Phase 1, backend foundation.

Two open questions should be settled before the code they affect gets written —
see `docs/DECISIONS.md` OPEN-001 (payment grain) and OPEN-002 (API error envelope).
