#!/usr/bin/env bash
###############################################################################
# Zero-downtime deployment for costonomy-mp-api (Tomcat 10, next to costonomy-api and costonomy-common-api)
#
# A copy of costonomy-api's deploy.sh (via costonomy-common-api's) with this app's settings (APP block below);
# keep the three in step.
#
# Mechanism: Tomcat *parallel deployment*. A build is deployed as a versioned
# WAR (costonomy-mp-api##<timestamp>.war) ALONGSIDE the running one. Tomcat starts
# the new version while the old keeps serving every in-flight request, then new
# traffic moves to the new version. The old context is only removed AFTER the
# new one is confirmed healthy — so there is never a moment with zero live
# versions, hence no downtime.
#
# Readiness is confirmed by polling GET /actuator/health and matching the exact
# build time GET /actuator/info reports against the freshly built WAR (the
# build-info goal in pom.xml). No log scraping, no guessing which
# version answered.
#
# Memory: parallel deployment briefly runs TWO copies of the app in one Tomcat.
# A pre-flight guard reads the Tomcat service's memory cap (systemd MemoryMax)
# and current use, and refuses to deploy unless there is room for another copy
# (NEW_COPY_MB): past the cap systemd kills the whole Tomcat. Same check in
# the other apps' deploy.sh; they all share the server's deploy lock.
#
# Usage: ./deploy.sh <test|prod> [option]     (the target is required; nothing runs without it)
#   ./deploy.sh test                 Build, then zero-downtime deploy to the test server (testapi.costonomy.com)
#   ./deploy.sh prod                 Build, then zero-downtime deploy to production (api.costonomy.com)
#   ./deploy.sh <env> --skip-build   Deploy the existing target/ WAR (no rebuild)
#   ./deploy.sh <env> --rollback     Redeploy the last-good backup (zero downtime)
#   ./deploy.sh <env> --dry-run      Run all read-only checks + print the plan; change nothing
#   ./deploy.sh <env> --list         List versions currently on the server
#   ./deploy.sh <env> --status       Show active version + health
#   ./deploy.sh <env> --force        Skip the pre-flight RAM guard (use with care)
###############################################################################
set -euo pipefail
cd "$(dirname "$0")"                          # paths below are relative to the repo root

# ── Target server (required first argument) ──────────────────────────────
TARGET="${1:-}"
case "$TARGET" in
    test)
        EC2_HOST="15.252.180.221"                                   # testapi.costonomy.com (nginx → Tomcat :8080)
        SSH_KEY="$HOME/Downloads/test-server.pem"
        TOMCAT_PORT=8080
        TOMCAT_SERVICE="tomcat"
        ;;
    prod)
        EC2_HOST="13.232.143.65"                                    # api.costonomy.com (Tomcat :8081)
        SSH_KEY="$HOME/Downloads/costonomy-services.pem"
        TOMCAT_PORT=8081
        TOMCAT_SERVICE="tomcat10"
        ;;
    -h|--help)
        sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *)
        echo "Usage: $0 <test|prod> [--skip-build|--rollback|--dry-run|--list|--status|--force]" >&2
        echo "The target server is required: test (testapi.costonomy.com) or prod (api.costonomy.com)." >&2
        exit 1 ;;
esac
shift

# ── Configuration ─────────────────────────────────────────────────────────
EC2_USER="ubuntu"
TOMCAT_HOME="/opt/tomcat10"
WEBAPPS="${TOMCAT_HOME}/webapps"
CATALINA_OUT="${TOMCAT_HOME}/logs/catalina.out"
APP_NAME="costonomy-mp-api"
CONTEXT_PATH="costonomy-mp-api"               # URL context (…/costonomy-mp-api/actuator/health)
LOCAL_WAR="target/costonomy-mp-api.war"
BUILD_INFO="target/classes/META-INF/build-info.properties"
# JDK 17: on a newer one Lombok stops working silently (CLAUDE.md).
JDK17="$(/usr/libexec/java_home -v 17 2>/dev/null || echo "${JAVA_HOME:-}")"
BUILD_CMD="env JAVA_HOME=${JDK17} mvn -q clean package -DskipTests"
HEALTH_URL="http://localhost:${TOMCAT_PORT}/${CONTEXT_PATH}/actuator/health"
VERSION_URL="http://localhost:${TOMCAT_PORT}/${CONTEXT_PATH}/actuator/info"   # where the build's time is reported…
VERSION_KEY="time"                            # …under this JSON key ({"build":{"time":…}})
NEW_COPY_MB=700                               # memory a second copy needs during the overlap
ROLLBACK_SRC="target/.rollback-src.war"

BACKUP_DIR="${TOMCAT_HOME}/backups"
STATE_DIR="${TOMCAT_HOME}/deploy-state"       # lock (shared by every app) + per-app last-good pointer
LAST_GOOD="${STATE_DIR}/${APP_NAME}.last-good"
KEEP_BACKUPS=5

HEALTH_TIMEOUT=240                            # max seconds to wait for readiness
HEALTH_INTERVAL=5                             # seconds between polls
DRAIN_WAIT=12                                 # let in-flight requests finish
UNDEPLOY_SETTLE=3                             # poll interval while Tomcat undeploys the old context
UNDEPLOY_TIMEOUT=90                           # then remove its folder anyway

SSH_OPTS="-i ${SSH_KEY} -o StrictHostKeyChecking=no -o ConnectTimeout=10 -o ServerAliveInterval=15 -o ServerAliveCountMax=6"
# ──────────────────────────────────────────────────────────────────────────

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
log()   { echo -e "${GREEN}[DEPLOY]${NC} $*"; }
info()  { echo -e "${BLUE}[INFO]${NC}   $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}   $*"; }
error() { echo -e "${RED}[ERROR]${NC}  $*"; }
die()   { error "$*"; exit 1; }

ssh_cmd() { ssh ${SSH_OPTS} "${EC2_USER}@${EC2_HOST}" "$@"; }
scp_up()  { scp ${SSH_OPTS} "$1" "${EC2_USER}@${EC2_HOST}:$2"; }
scp_down(){ scp ${SSH_OPTS} "${EC2_USER}@${EC2_HOST}:$1" "$2"; }

# HTTP status of the health endpoint on the server (000 = unreachable)
health_code() {
    local c; c="$(ssh_cmd "curl -s --max-time 5 -o /dev/null -w '%{http_code}' '${HEALTH_URL}' || true" 2>/dev/null || true)"
    echo "${c:-000}"
}
# Full JSON body of the health endpoint
health_body() {
    ssh_cmd "curl -s --max-time 5 '${HEALTH_URL}'" 2>/dev/null || echo ""
}
# JSON body of the endpoint that reports the running build's time
version_body() {
    ssh_cmd "curl -s --max-time 5 '${VERSION_URL}'" 2>/dev/null || echo ""
}
# Extract the build time (ISO instant string, under VERSION_KEY) from that body
extract_build_time() {
    echo "$1" | sed -n "s/.*\"${VERSION_KEY}\"[[:space:]]*:[[:space:]]*\"\([^\"]*\)\".*/\1/p" | head -1
}

# ── Parse args ────────────────────────────────────────────────────────────
SKIP_BUILD=false; DO_ROLLBACK=false; DO_LIST=false; DO_STATUS=false; FORCE=false; DRY_RUN=false
for arg in "$@"; do
    case "$arg" in
        --skip-build) SKIP_BUILD=true ;;
        --rollback)   DO_ROLLBACK=true ;;
        --list)       DO_LIST=true ;;
        --status)     DO_STATUS=true ;;
        --force)      FORCE=true ;;
        --dry-run)    DRY_RUN=true ;;
        -h|--help)    sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) die "Unknown option: $arg (try --help)" ;;
    esac
done

log "Target: ${TARGET} (${EC2_HOST}, Tomcat :${TOMCAT_PORT})"

# In dry-run, prefix mutating log lines so it's obvious nothing changed.
DR="";  [ "$DRY_RUN" = true ] && DR="[DRY-RUN] would: "

# ── Read-only server queries ──────────────────────────────────────────────
active_build_time() { extract_build_time "$(version_body)"; }  # what the running build reports now
list_versions() {
    ssh_cmd "ls -1 ${WEBAPPS}/${APP_NAME}##*.war 2>/dev/null | sed 's|.*/||' || true"
}

if [ "$DO_LIST" = true ]; then
    log "Versioned WARs on ${EC2_HOST}:"
    V="$(list_versions)"; [ -n "$V" ] && echo "$V" | sed 's/^/  /' || echo "  (none — unversioned or fresh)"
    exit 0
fi
if [ "$DO_STATUS" = true ]; then
    log "Health HTTP:      $(health_code)"
    log "Active buildTime: $(active_build_time || echo unknown)"
    log "Deployed WARs:";   list_versions | sed 's/^/  /'
    exit 0
fi

# ── Distributed lock: never let two deploys run at once ───────────────────
LOCK_HELD=false
acquire_lock() {
    ssh_cmd "sudo mkdir -p '${STATE_DIR}' && sudo chown ${EC2_USER}:${EC2_USER} '${STATE_DIR}'"
    # mkdir is atomic; if the lock dir already exists another deploy holds it.
    if ! ssh_cmd "mkdir '${STATE_DIR}/lock' 2>/dev/null"; then
        die "Another deploy holds the lock (${STATE_DIR}/lock). If stale: ssh in and 'rmdir ${STATE_DIR}/lock'."
    fi
    LOCK_HELD=true
}
release_lock() { [ "$LOCK_HELD" = true ] && ssh_cmd "rmdir '${STATE_DIR}/lock' 2>/dev/null || true"; }
trap release_lock EXIT

# ── Pre-flight: reachability + memory headroom ────────────────────────────
preflight() {
    log "Pre-flight checks…"
    ssh_cmd "true" || die "Cannot SSH to ${EC2_USER}@${EC2_HOST}."

    local code; code="$(health_code)"
    if [ "$code" = "200" ]; then
        info "Current app healthy (HTTP 200)."
    elif [ -z "$(list_versions)" ] && ! ssh_cmd "sudo test -e '${WEBAPPS}/${APP_NAME}.war'"; then
        info "First deploy of ${APP_NAME} on this server (nothing deployed yet)."
    else
        warn "Current /health is HTTP ${code} (expected 200) — running app looks unhealthy."
        if [ "$DRY_RUN" = true ]; then
            warn "[DRY-RUN] a real deploy would ABORT here (unless --force)."
        else
            [ "$FORCE" = true ] || die "Refusing to deploy onto an unhealthy server. Use --force to override."
        fi
    fi

    if [ "$FORCE" = true ]; then warn "Skipping memory guard (--force)."; return; fi
    # The Tomcat service's cap (systemd MemoryMax) is what kills the whole Tomcat; the machine's free RAM matters too.
    local max cur avail_mb room_mb problem=""
    max="$(ssh_cmd "systemctl show '${TOMCAT_SERVICE}' -p MemoryMax --value")"
    cur="$(ssh_cmd "systemctl show '${TOMCAT_SERVICE}' -p MemoryCurrent --value")"
    avail_mb="$(ssh_cmd "free -m | awk '/^Mem:/ {print \$7}'")"
    if [[ "$max" =~ ^[0-9]+$ ]] && [[ "$cur" =~ ^[0-9]+$ ]]; then
        room_mb=$(( (max - cur) / 1048576 ))
        info "Tomcat (${TOMCAT_SERVICE}): using $((cur / 1048576))MB of its $((max / 1048576))MB cap → ${room_mb}MB left | machine available: ${avail_mb}MB | a second copy needs ~${NEW_COPY_MB}MB."
        [ "$room_mb" -ge "$NEW_COPY_MB" ] || problem="Tomcat's cap leaves ${room_mb}MB (< ${NEW_COPY_MB}MB): raise MemoryMax in the ${TOMCAT_SERVICE} service."
    else
        info "Tomcat (${TOMCAT_SERVICE}): no memory cap | machine available: ${avail_mb}MB | a second copy needs ~${NEW_COPY_MB}MB."
    fi
    [ "${avail_mb:-0}" -ge "$NEW_COPY_MB" ] || problem="${problem:+$problem }The machine has only ${avail_mb}MB available."
    if [ -n "$problem" ]; then
        error "Not enough memory for a safe parallel deploy. ${problem}"
        if [ "$DRY_RUN" = true ]; then
            warn "[DRY-RUN] a real deploy would ABORT here (unless --force)."
        else
            die "Aborted before touching the server. Use --force to override at your own risk."
        fi
    else
        info "Memory headroom OK."
    fi
}

# ── Dry-run: print exactly what a real deploy would change, mutate nothing ──
# args: <local_war> <expected_build_time|""> <version_label>
dry_run_plan() {
    local war="$1" expected_bt="$2" version="$3"
    local versioned="${APP_NAME}##${version}.war"
    local prev latest_prev has_unversioned
    prev="$(list_versions | sort)"
    latest_prev="$(printf '%s\n' "$prev" | tail -1)"
    has_unversioned="$(ssh_cmd "test -f '${WEBAPPS}/${APP_NAME}.war' && echo yes || echo no")"

    echo ""
    log  "════════════ DRY-RUN PLAN (no server changes) ════════════"
    info "Target host    : ${EC2_USER}@${EC2_HOST}"
    info "Webapps dir    : ${WEBAPPS}"
    info "Local WAR      : ${war} ($(du -h "$war" | cut -f1))"
    info "Build fingerprint: ${expected_bt:-<none — would use fallback HTTP-200+dir gate>}"
    info "New version    : ${versioned}"
    info "Currently deployed:"
    [ -n "$prev" ] && printf '%s\n' "$prev" | sed 's/^/                   /' || echo "                   (none)"
    [ "$has_unversioned" = yes ] && warn "Legacy unversioned ${APP_NAME}.war present — would be removed after cutover."
    echo ""
    log  "Would perform, in order:"
    echo "  1. ${DR}acquire deploy lock (${STATE_DIR}/lock)"
    if [ -n "$latest_prev" ]; then
        echo "  2. ${DR}back up ${latest_prev} → ${BACKUP_DIR}/ and record it as last-good"
    else
        echo "  2. (no current version to back up)"
    fi
    echo "  3. ${DR}scp ${war} → /tmp, then cp into ${WEBAPPS}/${versioned}"
    echo "  4. ${DR}poll ${HEALTH_URL} until buildTime=${expected_bt:-<fallback>} (timeout ${HEALTH_TIMEOUT}s)"
    echo "  5a. on healthy → ${DR}drain ${DRAIN_WAIT}s, then remove old version(s):"
    if [ -n "$prev" ]; then printf '%s\n' "$prev" | sed 's/^/         - /'; else echo "         - (none)"; fi
    [ "$has_unversioned" = yes ] && echo "         - ${APP_NAME}.war (legacy unversioned)"
    echo "  5b. on failure → ${DR}remove ONLY ${versioned}; old version keeps serving (no downtime)"
    echo "  6. ${DR}prune backups to the newest ${KEEP_BACKUPS}"
    echo ""
    log  "════════════ Nothing was changed on the server ════════════"
}

# ── Core: deploy a local WAR as a new parallel version, health-gate, cut over ─
# args: <local_war> <expected_build_time|""> <version_label>
# Returns 0 on healthy cutover; non-zero after performing auto-rollback.
deploy_war() {
    local war="$1" expected_bt="$2" version="$3"
    local versioned="${APP_NAME}##${version}.war"
    local staging="/tmp/${versioned}"

    ssh_cmd "sudo mkdir -p '${BACKUP_DIR}' && sudo chown ${EC2_USER}:${EC2_USER} '${BACKUP_DIR}'"

    # Back up the currently active WAR so --rollback has a target.
    local latest_prev; latest_prev="$(list_versions | sort | tail -1)"
    if [ -n "$latest_prev" ]; then
        log "Backing up current version: ${latest_prev}"
        ssh_cmd "sudo cp '${WEBAPPS}/${latest_prev}' '${BACKUP_DIR}/${latest_prev}' 2>/dev/null || true"
        printf '%s\n' "$latest_prev" | ssh_cmd "cat > '${LAST_GOOD}'"
    fi

    log "Uploading WAR (${version})…"
    scp_up "$war" "$staging"

    log "Deploying ${versioned} alongside the running version…"
    ssh_cmd "sudo cp '${staging}' '${WEBAPPS}/${versioned}' && sudo chown root:root '${WEBAPPS}/${versioned}' && rm -f '${staging}'"

    # ── Readiness gate: wait until /health reports the exact new buildTime ──
    log "Waiting for new version to report healthy (timeout ${HEALTH_TIMEOUT}s)…"
    local elapsed=0 healthy=false
    sleep 5
    while [ "$elapsed" -lt "$HEALTH_TIMEOUT" ]; do
        local body code bt
        body="$(version_body)"; code="$(health_code)"; bt="$(extract_build_time "$body")"
        if [ "$code" = "200" ]; then
            if [ -n "$expected_bt" ]; then
                [ "$bt" = "$expected_bt" ] && { healthy=true; break; }
            else
                # No build-info to match: fall back to "context exploded + HTTP 200".
                ssh_cmd "sudo test -d '${WEBAPPS}/${APP_NAME}##${version}'" && { healthy=true; break; }
            fi
        fi
        # Fast-fail if Tomcat logged a deploy error for this exact version.
        if ssh_cmd "sudo grep -q 'Error deploying.*${APP_NAME}##${version}' '${CATALINA_OUT}' 2>/dev/null"; then
            error "Tomcat reported a deploy error for ${version}."; break
        fi
        elapsed=$((elapsed + HEALTH_INTERVAL))
        printf "\r  …%ds  (serving buildTime=%s | want=%s)      " "$elapsed" "${bt:-?}" "${expected_bt:-any}"
        sleep "$HEALTH_INTERVAL"
    done
    echo ""

    if [ "$healthy" != true ]; then
        error "New version did not become healthy. Auto-rolling back…"
        ssh_cmd "sudo rm -f '${WEBAPPS}/${versioned}'; sudo rm -rf '${WEBAPPS}/${APP_NAME}##${version}/'"
        sleep "$UNDEPLOY_SETTLE"
        local oc; oc="$(health_code)"
        if [ "$oc" = "200" ]; then
            warn "Rolled back. Previous version still serving (HTTP 200). No downtime."
        else
            error "Previous version now returns HTTP ${oc}. Manual check needed — backups in ${BACKUP_DIR}."
        fi
        return 1
    fi

    log "New version healthy (buildTime=${expected_bt:-n/a}). Draining old version ${DRAIN_WAIT}s…"
    sleep "$DRAIN_WAIT"

    log "Removing old version(s)…"
    ssh_cmd "
        # Legacy unversioned deployment, if any.
        sudo rm -f  '${WEBAPPS}/${APP_NAME}.war'
        sudo rm -rf '${WEBAPPS}/${APP_NAME}/'
        # All versioned WARs except the one we just shipped.
        for f in ${WEBAPPS}/${APP_NAME}##*.war; do
            [ -e \"\$f\" ] || continue
            [ \"\$f\" = '${WEBAPPS}/${versioned}' ] && continue
            sudo rm -f \"\$f\"
        done
    "
    # Tomcat notices a removed WAR only on its next background check (~10 s), then stops that version (Spring closes
    # its pools and schedulers) and deletes the unpacked folder itself. Deleting the folder before that leaves
    # Spring unable to load the classes it needs to shut down, and the old version keeps running in the JVM.
    log "Waiting for Tomcat to undeploy the old version(s) (up to ${UNDEPLOY_TIMEOUT}s)…"
    local waited=0
    while [ "$waited" -lt "$UNDEPLOY_TIMEOUT" ]; do
        ssh_cmd "for d in ${WEBAPPS}/${APP_NAME}##*/; do [ -d \"\$d\" ] || continue; [ \"\$(basename \"\$d\")\" = '${APP_NAME}##${version}' ] || exit 1; done; exit 0" && break
        sleep "$UNDEPLOY_SETTLE"
        waited=$((waited + UNDEPLOY_SETTLE))
    done
    [ "$waited" -lt "$UNDEPLOY_TIMEOUT" ] || warn "Tomcat hasn't removed the old version's folder after ${UNDEPLOY_TIMEOUT}s; removing it now."
    ssh_cmd "
        for d in ${WEBAPPS}/${APP_NAME}##*/; do
            [ -d \"\$d\" ] || continue
            [ \"\$(basename \"\$d\")\" = '${APP_NAME}##${version}' ] && continue
            sudo rm -rf \"\$d\"
        done
    "

    # Record new version as last-good and prune backups.
    printf '%s\n' "$versioned" | ssh_cmd "cat > '${LAST_GOOD}'"
    ssh_cmd "cd '${BACKUP_DIR}' && ls -t ${APP_NAME}##*.war 2>/dev/null | tail -n +$((KEEP_BACKUPS + 1)) | xargs -r sudo rm -f"

    sleep 3
    local fc; fc="$(health_code)"
    [ "$fc" = "200" ] || { error "Post-cleanup health is HTTP ${fc}."; return 2; }
    return 0
}

# ── Mode: rollback to last-good backup ────────────────────────────────────
run_rollback() {
    [ "$DRY_RUN" = true ] || acquire_lock
    local last; last="$(ssh_cmd "cat '${LAST_GOOD}' 2>/dev/null || true")"
    # Before last-good was per app, Core's pointer was deploy-state/last-good.
    [ -n "$last" ] || last="$(ssh_cmd "cat '${STATE_DIR}/last-good' 2>/dev/null | grep '^${APP_NAME}##' || true")"
    [ -n "$last" ] || die "No last-good record at ${LAST_GOOD} — nothing to roll back to."
    ssh_cmd "test -f '${BACKUP_DIR}/${last}'" || die "Backup not found: ${BACKUP_DIR}/${last}"

    local active_war; active_war="$(list_versions | sort | tail -1)"
    if [ "$active_war" = "$last" ]; then
        warn "Last-good (${last}) is already the active version. Nothing to roll back."; exit 0
    fi

    if [ "$DRY_RUN" = true ]; then
        echo ""
        log  "════════════ DRY-RUN ROLLBACK PLAN (no server changes) ════════════"
        info "Currently active : ${active_war:-<none>}"
        info "Would roll back to: ${last} (from ${BACKUP_DIR}/)"
        echo "  1. ${DR}pull ${BACKUP_DIR}/${last} down and redeploy it as a new parallel version"
        echo "  2. ${DR}health-gate it, then remove the current version (${active_war:-<none>})"
        log  "════════════ Nothing was changed on the server ════════════"
        return 0
    fi

    warn "Rolling back to: ${last}"

    # Pull the backup local, redeploy it as a NEW parallel version (fresh label
    # so Tomcat treats it as newest), health-gate, then drop the current one.
    local src="${ROLLBACK_SRC}"
    scp_down "${BACKUP_DIR}/${last}" "$src"
    local version="rb$(date +%Y%m%d%H%M%S)"
    if deploy_war "$src" "" "$version"; then
        rm -f "$src"
        echo ""; log "════════════════════════════════════════"
        log "  ROLLBACK SUCCESSFUL → ${last}"
        log "════════════════════════════════════════"
    else
        rm -f "$src"
        die "Rollback deploy failed — see messages above."
    fi
}

# ── Mode: normal deploy ───────────────────────────────────────────────────
run_deploy() {
    if [ "$SKIP_BUILD" = false ]; then
        log "Building (${BUILD_CMD})…"
        ${BUILD_CMD} || die "Build failed."
        log "Build OK."
    else
        log "Skipping build (--skip-build)."
    fi
    [ -f "$LOCAL_WAR" ] || die "WAR not found at ${LOCAL_WAR} (run without --skip-build)."

    # The exact ISO instant this WAR reports at /health — used to gate readiness.
    # build.time in build-info.properties escapes ':' as '\:'; unescape it so it
    # matches the time /actuator/info reports.
    local expected_bt=""
    if [ -f "$BUILD_INFO" ]; then
        expected_bt="$(sed -n 's/^build.time=//p' "$BUILD_INFO" | sed 's/\\:/:/g' | tr -d '\r')"
    fi
    if [ -z "$expected_bt" ]; then
        warn "No build.time in ${BUILD_INFO}; using fallback readiness gate (HTTP 200 + exploded dir)."
    fi

    [ "$DRY_RUN" = true ] || acquire_lock
    preflight

    local version; version="$(date +%Y%m%d%H%M%S)"

    if [ "$DRY_RUN" = true ]; then
        dry_run_plan "$LOCAL_WAR" "$expected_bt" "$version"
        return 0
    fi

    log "Deploying version ${version} (expected buildTime=${expected_bt:-fallback})"
    info "WAR: ${LOCAL_WAR} ($(du -h "$LOCAL_WAR" | cut -f1))"

    if deploy_war "$LOCAL_WAR" "$expected_bt" "$version"; then
        echo ""; log "════════════════════════════════════════"
        log "  DEPLOYMENT SUCCESSFUL — zero downtime"
        log "  Version: ${version}"
        log "  Health:  HTTP 200"
        log "════════════════════════════════════════"
    else
        echo ""; error "════════════════════════════════════════"
        error "  DEPLOYMENT FAILED — rolled back"
        error "  Old version kept serving; no downtime"
        error "════════════════════════════════════════"
        exit 1
    fi
}

# ── Dispatch ──────────────────────────────────────────────────────────────
if [ "$DO_ROLLBACK" = true ]; then run_rollback; else run_deploy; fi
