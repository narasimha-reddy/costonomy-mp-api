#!/usr/bin/env bash
# First-pass scan for database bottleneck patterns in costonomy-mp-api. Run from the repo root:
#   .claude/skills/db-bottleneck-review/scripts/audit.sh [section]
# Sections: nplus1 unbounded like jobs tx locks deletes batching indexes purge all (default).
# Output is capped per check so it stays readable. Every hit is a LEAD to read, not a verdict.
set -u
SRC=src/main/java
MIG=src/main/resources/db/migration
cap() { head -n "${CAP:-25}"; }
hdr() { printf '\n== %s ==\n' "$1"; }
section=${1:-all}
want() { [ "$section" = all ] || [ "$section" = "$1" ]; }

if want nplus1; then
  hdr "N+1: repository/JdbcTemplate calls inside lambdas or loops (read each hit)"
  grep -rnE "\.(map|forEach|flatMap)\(.*(\.find[A-Za-z]*\(|\.get[A-Z][A-Za-z]*\(\)\)|jdbc\.query)" $SRC --include=*.java | grep -E "find|jdbc" | cap
  hdr "N+1: single-row lookups by id in a for loop"
  grep -rnE "for \(.*:.*\)" -A4 $SRC --include=*.java | grep -E "findById\(|findBy[A-Za-z]*\(|queryForObject" | cap
fi

if want unbounded; then
  hdr "Unbounded reads: findAll(), List-returning findBy... with no Pageable (check the caller for a limit)"
  grep -rnE "\.findAll\(\)" $SRC --include=*.java | cap
  grep -rnE "List<[A-Za-z]+> findBy[A-Za-z]*\(" $SRC --include=*Repository.java | grep -v "Pageable" | cap
  hdr "Page<> return types (each pays an extra count(*))"
  grep -rnE "Page<[A-Za-z]+> " $SRC --include=*Repository.java | cap
fi

if want like; then
  hdr "Leading-wildcard or lower() LIKE (cannot use an index)"
  grep -rnEi "like +'%|like +concat\('%|lower\([a-z_.]+\) like" $SRC --include=*.java | cap
  hdr "Optional-filter idiom '? is null or col = ?' (defeats indexes)"
  grep -rnEi "\? is null or|:[a-zA-Z]+ is null or" $SRC --include=*.java | cap
fi

if want jobs; then
  hdr "Scheduled jobs (check: bounded? ordered by an indexed column? transaction around slow work?)"
  grep -rn "@Scheduled" $SRC --include=*.java | cap
  hdr "Scheduled classes that are also @Transactional (long transaction risk)"
  for f in $(grep -rl "@Scheduled" $SRC --include=*.java); do grep -l "@Transactional" "$f"; done | cap
fi

if want tx; then
  hdr "REQUIRES_NEW (doubles connection use under load; pool is small)"
  grep -rn "Propagation.REQUIRES_NEW" $SRC --include=*.java | cap
  hdr "Hikari settings"
  grep -n "hikari\|scheduling.pool" src/main/resources/application*.properties | cap
fi

if want locks; then
  hdr "Pessimistic locks (hot rows? held across external calls? lock order?)"
  grep -rnE "PESSIMISTIC_(WRITE|READ|FORCE)|for update|lockById|lockBy" $SRC --include=*.java | cap
fi

if want deletes; then
  hdr "Derived deleteBy... (SELECT then delete row by row) and bulk deletes without LIMIT"
  grep -rnE "void deleteBy|int deleteBy|long deleteBy" $SRC --include=*.java | cap
  grep -rniE "delete from" $SRC --include=*.java | grep -vi "limit" | cap
fi

if want batching; then
  hdr "saveAll / save inside loops (IDENTITY keys disable insert batching)"
  grep -rnE "saveAll\(|\.save\(" $SRC --include=*.java | grep -E "for |forEach|stream" | cap
  grep -n "batch_size\|batch_fetch" src/main/resources/application*.properties
fi

if want indexes; then
  hdr "Indexes declared in migrations, by table (compare with the predicates you found)"
  grep -rhiE "create (unique )?index|add (unique )?(index|key|constraint)|unique key|  key |index +[a-z_]+ *\(" $MIG | sed -E 's/^ +//' | cap
  echo "(For the real picture run EXPLAIN against the local database. See SKILL.md.)"
fi

if want purge; then
  hdr "Retention: deleteExpired / purge / cleanup callers"
  grep -rniE "deleteExpired|purge|retention|cleanup" $SRC --include=*.java | cap
fi
