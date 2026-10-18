#!/bin/bash
# Runs Marketplace locally, with costonomy-common-api (sign-in) and costonomy-api from the shared Docker stack in
# costonomy-api/local. Settings: this repo's .env.local (template .env.example), which names that checkout in
# COSTONOMY_API_REPO. Every start first runs the stack's checks: the dev MySQL only (never production's), the dev
# databases, no CHANGE ME left, S3 settings complete, Docker, free ports.
#
#   ./run.sh                 everything in Docker: common-api, Core and MP (http://localhost:7070/costonomy-mp-api)
#   ./run.sh --build         the same, building the WARs first (after pulling or changing code)
#   ./run.sh source          MP from source (mvn spring-boot:run; a recompile restarts it in seconds), with the
#                            stack's common-api and Core started if they aren't running
#   ./run.sh check           only the checks
#   ./run.sh logs [service]  follow the stack's logs (mp-api, common-api, costonomy-api)
#   ./run.sh stop            stop the stack
set -euo pipefail
cd "$(dirname "$0")"
fail() { echo "$*" >&2; exit 1; }
value() { sed -nE "s/^(export +)?$1=//p" .env.local 2>/dev/null | tail -1 | sed -E "s/^['\"]//; s/['\"]$//"; }

[ -f .env.local ] || fail "Missing .env.local: cp .env.example .env.local, then fill in the CHANGE ME lines (docs/ONBOARDING.md)."
core="$(value COSTONOMY_API_REPO)"; core="${core/#\~/$HOME}"
[ -x "$core/local/run.sh" ] || fail "COSTONOMY_API_REPO=${core:-(not set)} in .env.local isn't a costonomy-api checkout (it runs the stack)."
grep -qE "^MP_API_REPO=" "$core/.env.local" 2>/dev/null \
  || fail "Add MP_API_REPO=$PWD to $core/.env.local so the stack knows this checkout."

health() { curl -s -o /dev/null -m 3 -w '%{http_code}' "$1" || true; }

case "${1:-}" in
  ""|--build) exec "$core/local/run.sh" dev ${1:+"$1"} ;;
  check|stop|logs) exec "$core/local/run.sh" "$@" ;;
  source)
    "$core/local/check.sh" dev || fail "Not starting: fix what the checks listed above."
    if [ "$(health http://localhost:8082/costonomy-common-api/actuator/health)" != 200 ]; then
      echo "common-api isn't running: starting the stack first…"
      "$core/local/run.sh" dev
    fi
    # The stack's MP container would hold port 7070: this run replaces it.
    docker stop costonomy-local-mp-api-1 >/dev/null 2>&1 || true
    export JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || echo "${JAVA_HOME:-}")"   # Lombok needs 17
    set -a; . ./.env.local; set +a
    exec mvn spring-boot:run ;;
  *) fail "usage: $0 [--build] | source | check | logs [service] | stop" ;;
esac
