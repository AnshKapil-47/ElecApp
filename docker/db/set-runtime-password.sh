#!/bin/sh
# One-shot deployment step that runs after the Flyway migration and before the backend starts.
#
# V2__runtime_role.sql creates the restricted runtime role with a development password that is public in
# the repository. This step gives the role its real password (DB_PASSWORD) and re-asserts that it has no
# elevated attributes. Table and function privileges still come from V2/V6 only.
#
# Environment: PGHOST, PGDATABASE, PGUSER, PGPASSWORD (privileged bootstrap account),
#              RUNTIME_DB_USER, RUNTIME_DB_PASSWORD.
set -eu

if [ "$RUNTIME_DB_PASSWORD" = "elecsarthi_app_dev_pw" ] || [ "${#RUNTIME_DB_PASSWORD}" -lt 16 ]; then
    echo "DB_PASSWORD must be a unique secret of at least 16 characters (not the development password)." >&2
    exit 1
fi

psql -X -v ON_ERROR_STOP=1 -v role="$RUNTIME_DB_USER" -v pw="$RUNTIME_DB_PASSWORD" <<'SQL'
ALTER ROLE :"role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'pw';
SQL
echo "Runtime role password set."
