#!/bin/sh
# Schema tests for ElecSarthi. Creates a throwaway database, applies every Flyway migration
# in version order, runs tools/db/test_schema.sql, prints PASS lines and a summary.
#
# Needs: PostgreSQL 16+ server reachable with psql, a superuser login (tests create a role).
# Configure with the usual libpq variables, e.g.:
#   PGHOST=localhost PGPORT=5432 PGUSER=postgres PGPASSWORD=... tools/db/run_tests.sh
# The test database name is $TEST_DB (default es_test). It is dropped and recreated.
set -e
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
MIG="$ROOT/src/main/resources/db/migration"
DB=${TEST_DB:-es_test}
PSQL="psql -v ON_ERROR_STOP=1 -q"

$PSQL -d postgres -c "DROP DATABASE IF EXISTS $DB;" -c "CREATE DATABASE $DB;"
# apply V1, V2, V3, ... in numeric version order
for f in $(ls "$MIG"/V*__*.sql | sed 's#.*/V\([0-9]*\)__#\1 &#' | sort -n | cut -d' ' -f2-); do
    $PSQL -d "$DB" -f "$f"
done
OUT=$(mktemp)
$PSQL -d "$DB" -f "$ROOT/tools/db/test_schema.sql" > "$OUT" 2>&1 || true
sed 's/^psql:[^:]*:[0-9]*: //' "$OUT" | grep -E "PASS|ERROR|accepted but" || true
PASS=$(grep -c "PASS" "$OUT" || true)
FAIL=$(grep -c -E "ERROR|accepted but" "$OUT" || true)
rm -f "$OUT"
echo "PASS: $PASS  failures: $FAIL"
[ "$FAIL" -eq 0 ]
