# Tests the real deployment chain (docker-compose.yml: postgres -> migration -> runtime-role -> backend)
# against a completely empty, disposable PostgreSQL 18.3 database.
#
# Usage: powershell -ExecutionPolicy Bypass -File .\tools\deploy\test_migration_job.ps1 [-WithBackend]
#
# Needs a running Docker daemon. Uses its own compose project, volume and generated one-off secrets; nothing is
# written to the repository. -WithBackend additionally builds the backend image and checks that it boots healthy
# with only the restricted runtime role (slow: the image is built from source).

param(
    [switch]$WithBackend
)

$ErrorActionPreference = "Continue"
$root = (Resolve-Path "$PSScriptRoot\..\..").Path
$project = "esmigtest"
$db = "es_deploy_test"
$work = Join-Path $env:TEMP ("es-migration-test-" + [guid]::NewGuid().ToString("N").Substring(0, 8))
New-Item -ItemType Directory -Path $work | Out-Null

function New-Secret([int]$bytes) {
    $buffer = New-Object byte[] $bytes
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($buffer)
    return ([System.BitConverter]::ToString($buffer) -replace "-", "").ToLower()
}

$bootstrapPw = New-Secret 24
$runtimePw = New-Secret 24
$envFile = Join-Path $work "test.env"
@(
    "POSTGRES_DB=$db", "POSTGRES_USER=postgres", "POSTGRES_PASSWORD=$bootstrapPw", "POSTGRES_PORT=55432",
    "DB_USER=elecsarthi_app", "DB_PASSWORD=$runtimePw",
    "ELECSARTHI_JWT_SECRET=$(New-Secret 32)", "ELECSARTHI_SECURITY_ACTIVATION_PEPPER=$(New-Secret 24)",
    "ELECSARTHI_SECURITY_ED25519_PRIVATE_KEY_HEX=$(New-Secret 64)",
    "ELECSARTHI_SECURITY_VOTER_MOBILE_AES_KEY_HEX=$(New-Secret 32)",
    "SERVER_PORT=18080", "GATEWAY_PORT=18081", "GATEWAY_SSL_PORT=18443"
) | Set-Content -Path $envFile -Encoding ascii

$script:failures = 0
function Check([string]$name, [bool]$ok, [string]$detail = "") {
    if ($ok) { Write-Host "  PASS  $name" -ForegroundColor Green }
    else { Write-Host "  FAIL  $name $detail" -ForegroundColor Red; $script:failures++ }
}

function Compose { docker compose -p $project -f "$root\docker-compose.yml" --env-file $envFile @args }
function Sql([string]$query) {
    docker exec -e PGPASSWORD=$bootstrapPw elecsarthi-postgres psql -h 127.0.0.1 -U postgres -d $db -X -At -c $query 2>&1
}
# Connects over the compose network, where password authentication applies (loopback inside the container is trust).
function NetworkLogin([string]$user, [string]$password) {
    $out = docker run --rm --network "${project}_elecsarthi-net" -e PGPASSWORD=$password postgres:18.3-bookworm `
        psql -h postgres -U $user -d $db -X -At -c "select current_user" 2>&1
    return ($LASTEXITCODE -eq 0 -and "$out".Trim() -eq $user)
}

try {
    docker info *> $null
    if ($LASTEXITCODE -ne 0) { throw "Docker daemon is not running." }
    Compose down -v *> $null

    Write-Host "1. Empty PostgreSQL 18.3: postgres -> migration -> runtime-role" -ForegroundColor Cyan
    Compose run --rm runtime-role *> "$work\happy.log"
    Check "deployment chain completes" ($LASTEXITCODE -eq 0) "(see $work\happy.log)"
    Check "server is PostgreSQL 18.3" ((Sql "show server_version") -match "^18\.3")
    Check "flyway history has 7 successful migrations" ((Sql "select count(*) from flyway_schema_history where success") -eq "7")
    Check "latest version is 7" ((Sql "select max(version::int) from flyway_schema_history") -eq "7")
    $tables = Sql "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public' and c.relkind in ('r','p') and not c.relispartition and c.relname <> 'flyway_schema_history'"
    Check "exactly 18 application tables" ($tables -eq "18") "(found $tables)"
    $roles = Sql "select count(*) from pg_class where relname = 'app_user'" # sanity: core table present
    Check "core tables present" ($roles -eq "1")

    Write-Host "2. Runtime role is restricted and has the deployed password" -ForegroundColor Cyan
    Check "no superuser / createdb / createrole" ((Sql "select rolsuper or rolcreatedb or rolcreaterole or rolreplication or rolbypassrls from pg_roles where rolname = 'elecsarthi_app'") -eq "f")
    Check "cannot create objects in schema public" ((Sql "select has_schema_privilege('elecsarthi_app','public','CREATE')") -eq "f")
    Check "sale lines and audit log are append-only for the runtime role" ((Sql "select has_table_privilege('elecsarthi_app','agent_sale_line','UPDATE') or has_table_privilege('elecsarthi_app','agent_sale_line','DELETE') or has_table_privilege('elecsarthi_app','audit_log','UPDATE') or has_table_privilege('elecsarthi_app','audit_log','DELETE')") -eq "f")
    Check "deployed DB_PASSWORD logs in" (NetworkLogin "elecsarthi_app" $runtimePw)
    Check "public development password is rejected" (-not (NetworkLogin "elecsarthi_app" "elecsarthi_app_dev_pw"))
    Check "bootstrap password does not work for the runtime role" (-not (NetworkLogin "elecsarthi_app" $bootstrapPw))

    Write-Host "3. Re-running the migration job is a no-op" -ForegroundColor Cyan
    $again = Compose run --rm --no-deps migration migrate 2>&1 | Out-String
    Check "schema reported up to date" ($again -match "up to date")
    Check "history unchanged" ((Sql "select count(*) from flyway_schema_history") -eq "7")

    Write-Host "4. A failing migration stops the deployment before the backend path" -ForegroundColor Cyan
    Compose down -v *> $null
    $bad = Join-Path $work "bad-migrations"
    New-Item -ItemType Directory -Path $bad | Out-Null
    Copy-Item "$root\src\main\resources\db\migration\*.sql" $bad
    Set-Content -Path "$bad\V99__deliberately_broken.sql" -Value "CREATE TABLE broken_test (id int);`nTHIS IS NOT VALID SQL;" -Encoding ascii
    $env:MIGRATIONS_DIR = $bad
    Compose run --rm runtime-role *> "$work\broken.log"
    $brokenExit = $LASTEXITCODE
    Remove-Item Env:\MIGRATIONS_DIR
    Check "compose reports failure" ($brokenExit -ne 0)
    Check "migration job exited non-zero" ((docker inspect elecsarthi-flyway --format "{{.State.ExitCode}}") -ne "0")
    Check "runtime-role step never ran" ((docker ps -a --format "{{.Names}}" | Select-String "runtime-role").Count -eq 0)
    Check "failed migration was rolled back" ((Sql "select to_regclass('public.broken_test') is null") -eq "t")

    Write-Host "5. The public development password is refused" -ForegroundColor Cyan
    Compose down -v *> $null
    $env:DB_PASSWORD = "elecsarthi_app_dev_pw"
    Compose run --rm runtime-role *> "$work\devpw.log"
    $devExit = $LASTEXITCODE
    Remove-Item Env:\DB_PASSWORD
    Check "deployment fails with the development password" ($devExit -ne 0)

    if ($WithBackend) {
        Write-Host "6. Backend boots healthy with the restricted role" -ForegroundColor Cyan
        Compose down -v *> $null
        Compose up -d --build backend *> "$work\backend-up.log"
        Check "backend container started" ($LASTEXITCODE -eq 0) "(see $work\backend-up.log)"
        $status = ""
        for ($i = 0; $i -lt 40; $i++) {
            $status = docker inspect elecsarthi-backend --format "{{.State.Health.Status}}" 2>$null
            if ($status -eq "healthy" -or $status -eq "unhealthy") { break }
            Start-Sleep -Seconds 5
        }
        Check "backend is healthy (ddl-auto=validate passed)" ($status -eq "healthy") "(status $status)"
        Check "backend connects only as the runtime role" ((Sql "select count(*) from pg_stat_activity where datname = '$db' and usename = 'elecsarthi_app'") -gt "0")
    }
}
finally {
    Compose down -v *> $null
    Remove-Item Env:\MIGRATIONS_DIR -ErrorAction SilentlyContinue
    Remove-Item Env:\DB_PASSWORD -ErrorAction SilentlyContinue
    Remove-Item -Recurse -Force $work -ErrorAction SilentlyContinue
}

if ($script:failures -gt 0) {
    Write-Host "`nDeployment migration test: $($script:failures) check(s) FAILED" -ForegroundColor Red
    exit 1
}
Write-Host "`nDeployment migration test: all checks passed" -ForegroundColor Green
exit 0
