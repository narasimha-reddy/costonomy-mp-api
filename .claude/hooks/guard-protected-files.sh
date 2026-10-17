#!/usr/bin/env bash
# PreToolUse (Edit|Write): block edits that CLAUDE.md treats as off-limits.
#  - an already-committed Flyway migration (D-002: history is immutable; add a new Vn instead)
#  - secrets / local config / logs
f=$(jq -r '.tool_input.file_path // empty')
[ -z "$f" ] && exit 0
root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
rel=${f#"$root"/}

case "$rel" in
  .env|.env.*|*/.env|*/.env.*|src/main/resources/application-local.properties|server.log|*.log|var/*)
    echo "Blocked: $rel is local config/secret/runtime data and must not be edited by the agent." >&2
    exit 2 ;;
  src/main/resources/db/migration/*.sql)
    if git ls-files --error-unmatch -- "$rel" >/dev/null 2>&1; then
      echo "Blocked: $rel is an already-committed Flyway migration. Never edit applied migrations (D-002); add a new V<n>__*.sql instead." >&2
      exit 2
    fi ;;
esac
exit 0
