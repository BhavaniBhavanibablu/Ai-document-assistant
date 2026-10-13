
$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env"

if (!(Test-Path $envFile)) {
    throw ".env file not found in project root."
}

Get-Content $envFile | ForEach-Object {
    $line = $_.Trim()

    if ($line -and !$line.StartsWith("#") -and $line.Contains("=")) {
        $parts = $line -split "=", 2
        $key = $parts[0].Trim()
        $value = $parts[1].Trim()

        if ($key -and $value) {
            [Environment]::SetEnvironmentVariable(
                $key, $value, "Process"
            )
        }
    }
}

if (!$env:DB_PASSWORD) {
    throw "DB_PASSWORD is missing from .env."
}

Write-Host "Environment variables loaded."
Write-Host "Running Maven tests..."

& "$PSScriptRoot\mvnw.cmd" clean test

if ($LASTEXITCODE -ne 0) {
    throw "Tests failed. Check the Maven error output."
}
