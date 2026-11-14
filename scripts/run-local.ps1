param([switch]$SkipDatabase)
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Read-EnvFile([string]$path) {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath $path) {
        if ($line -match '^\s*(#|$)') { continue }
        if ($line -notmatch '^([A-Z][A-Z0-9_]*)=(.*)$') { throw "Invalid env entry in $path" }
        $key = $Matches[1]
        $value = $Matches[2]
        if ($key -notmatch '^(AZAS_|SPRING_DATASOURCE_)') { throw "Unsupported local configuration key: $key" }
        if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or
                ($value.StartsWith("'") -and $value.EndsWith("'")))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$key] = $value
    }
    return $values
}

Push-Location $repoRoot
$previous = @{}
try {
    $values = Read-EnvFile (Join-Path $repoRoot '.env.example')
    if (Test-Path -LiteralPath '.env') {
        $overrides = Read-EnvFile (Join-Path $repoRoot '.env')
        foreach ($key in $overrides.Keys) { $values[$key] = $overrides[$key] }
    }
    foreach ($key in $values.Keys) {
        $current = [Environment]::GetEnvironmentVariable($key, 'Process')
        if ($null -eq $current) {
            $previous[$key] = $null
            [Environment]::SetEnvironmentVariable($key, $values[$key], 'Process')
        }
    }
    if (-not $SkipDatabase) {
        docker compose --env-file deploy/postgres.env --file deploy/compose.local.yaml up --detach --wait postgres
        if ($LASTEXITCODE -ne 0) { throw 'Local PostgreSQL startup failed' }
    }
    & .\gradlew.bat bootRun
    if ($LASTEXITCODE -ne 0) { throw 'Spring exited with an error' }
}
finally {
    foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    Pop-Location
}
