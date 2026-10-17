# Working in this repo with an AI agent

Index for agents (and humans). Root `CLAUDE.md` has the rules; this page says where things are
and what to avoid reading whole.

## Feedback loops (run these, in this order)

| Command | Needs Docker | Use it for |
|---|---|---|
| `mvn -o -q compile` | no | after each edit |
| `mvn -o test -DskipITs` | no | before saying "done"; includes `ArchitectureTest` |
| `mvn -o verify -Dit.test=<Class>` | yes | one integration test you are changing |
| `mvn clean install` | yes | whole suite, before committing (~5 min) |

Build with **JDK 17**. If you see hundreds of "cannot find symbol" on getters, it is the JDK, not the code.
On macOS with Homebrew the JDK is not visible to `java_home`; use
`export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`.

CI (`.github/workflows/ci.yml`) runs `compile`, `test`, `verify`: the same commands as above.

## Guardrails that are enforced, not just requested

- `.claude/settings.json`: permission allow/ask/deny lists; secrets, logs, `var/` and `target/` are unreadable.
- `.claude/hooks/guard-protected-files.sh`: blocks edits to committed Flyway migrations (add a new `V<n>` instead).
- `.claude/hooks/run-unit-tests.sh`: on stop, runs the unit suite if Java/SQL/properties/pom changed.
- `ArchitectureTest`: layering and module boundaries. Existing violations are frozen in
  `src/test/resources/archunit_store/`; **a new violation fails the build**. If you fix one, delete its line
  from the store file. Do not regenerate the store to make a failure go away.

## Large or noisy files: do not read whole

| File | Size | Do this instead |
|---|---|---|
| `CHANGELOG.md` | ~68 KB | `grep -n "D-<n>"` or read the top ~40 lines for recent changes |
| `CLAUDE.md` | ~35 KB | read the section you need |
| `docs/specs/*` | large | read via the order in `CLAUDE.md` "Read before writing code" |
| `server.log`, `var/`, `target/` | runtime output | never; they are denied in settings |

## Where things are

- Modules: `src/main/java/com/costonomy/mp/<module>/{domain,repository,service,web}`; `common` is the shared kernel.
- Schema: Flyway, `src/main/resources/db/migration/` (history is immutable, D-002).
- Local data: `python3 tools/seed-local.py` (needs the app running).
- Decisions and open questions: `docs/DECISIONS.md`. Specs: `docs/specs/`.
