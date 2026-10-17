#!/usr/bin/env bash
# Stop: run the fast unit suite (no Docker) before the agent declares done.
# Only runs when Java/SQL/pom files changed; on failure it feeds output back so the agent keeps working.
input=$(cat)
[ "$(echo "$input" | jq -r '.stop_hook_active // false')" = "true" ] && exit 0   # avoid infinite loops

cd "${CLAUDE_PROJECT_DIR:-.}" || exit 0
git status --porcelain 2>/dev/null | grep -qE '\.(java|sql|properties)$|pom\.xml$' || exit 0

# Build needs JDK 17 (Lombok breaks silently on 18+). Find it; if absent, skip rather than block on a false failure.
for j in "$(/usr/libexec/java_home -v 17 2>/dev/null)" /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home /usr/lib/jvm/java-17-openjdk*; do
  [ -x "$j/bin/java" ] && { export JAVA_HOME=$j; break; }
done
if ! "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '"17\.'; then
  echo "run-unit-tests: JDK 17 not found, skipping unit-test gate" >&2
  exit 0
fi
out=$(mvn -B -ntp -q test -DskipITs 2>&1); rc=$?
if [ $rc -ne 0 ]; then
  echo "Unit tests failed (mvn test -DskipITs). Fix before finishing:" >&2
  echo "$out" | tail -60 >&2
  exit 2
fi
exit 0
