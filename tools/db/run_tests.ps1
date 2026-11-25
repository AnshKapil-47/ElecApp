# PowerShell test runner for ElecSarthi schema tests
# Usage: .\tools\db\run_tests.ps1 [-Host 127.0.0.1] [-Port 5321] [-User postgres] [-Db es_test]

param(
    [string]$PgHost = "127.0.0.1",
    [int]$PgPort = 5321,
    [string]$PgUser = "postgres",
    [string]$TestDb = "es_test",
    [string]$PsqlPath = "C:\Program Files\PostgreSQL\18\bin\psql.exe"
)

$ErrorActionPreference = "Continue"
$root = Resolve-Path "$PSScriptRoot\..\.."
$migDir = "$root\src\main\resources\db\migration"
$testSql = "$root\tools\db\test_schema.sql"

Write-Host "Running schema tests on PostgreSQL at $PgHost`:$PgPort as $PgUser against $TestDb..." -ForegroundColor Cyan

# Drop and recreate test database
& $PsqlPath -h $PgHost -p $PgPort -U $PgUser -d postgres -c "DROP DATABASE IF EXISTS $TestDb;" -c "CREATE DATABASE $TestDb;" | Out-Null

# Apply migrations in order
$migrations = Get-ChildItem "$migDir\V*__*.sql" | Sort-Object { [int]($_.BaseName -replace '^V(\d+)__.*$', '$1') }
foreach ($mig in $migrations) {
    Write-Host "Applying $($mig.Name)..." -ForegroundColor Yellow
    & $PsqlPath -h $PgHost -p $PgPort -U $PgUser -d $TestDb -f $mig.FullName -v ON_ERROR_STOP=1 -q
}

# Run test_schema.sql
Write-Host "Executing tools/db/test_schema.sql..." -ForegroundColor Yellow
$out = & $PsqlPath -h $PgHost -p $PgPort -U $PgUser -d $TestDb -f $testSql 2>&1

$passCount = ($out | Select-String "PASS").Count
$failCount = ($out | Select-String -Pattern "ERROR|accepted but").Count

$out | ForEach-Object {
    if ($_ -match "PASS") {
        Write-Host $_ -ForegroundColor Green
    } elseif ($_ -match "ERROR|accepted but|FAIL") {
        Write-Host $_ -ForegroundColor Red
    }
}

$color = if ($failCount -eq 0) { "Green" } else { "Red" }
Write-Host "PASS: $passCount  failures: $failCount" -ForegroundColor $color

if ($failCount -gt 0) {
    exit 1
}
exit 0
