# Host-only regression: all Docker and GitHub calls are mocked; no stack is touched.
$ErrorActionPreference = 'Stop'
$ciSavedPat = $env:GITHUB_PAT_TOKEN
$env:GITHUB_PAT_TOKEN = 'synthetic-pat-for-regression'
function docker {
    $global:LASTEXITCODE = 0
    $ciCommand = $args -join ' '
    if ($ciCommand -eq 'volume ls -q') {
        'armoney-ci-artifacts'
        if ($global:ciMockScenario -eq 'startup-orphan' -or ($global:ciMockScenario -eq 'cleanup-leftover' -and $global:ciMockDown)) {
            'armoney-ci-buildmachine_docker-data'
        }
    } elseif ($ciCommand -like 'compose * down *') {
        $global:ciMockDown = $true
        if ($global:ciMockScenario -eq 'cleanup-failure') { $global:LASTEXITCODE = 1 }
    } elseif ($ciCommand -like 'compose * ps -aq runner') {
        'synthetic-container'
    } elseif ($ciCommand -like 'inspect *') {
        if ($global:ciMockScenario -eq 'early-exit') { 'false' } else { 'true' }
    } elseif ($ciCommand -like 'exec *') {
        $global:ciMockExecCalls++
    } elseif ($ciCommand -like 'wait *') {
        '0'
    } elseif ($ciCommand -like 'logs *') {
        'Synthetic TLS connection EOF; token synthetic-registration-token and synthetic-pat-for-regression must be redacted.'
    }
}
function Invoke-RestMethod {
    param($Method, $Uri, $Headers, $ErrorAction)
    if ($Uri -like '*/registration-token') {
        $global:ciMockRegistrations++
        return @{ token = 'synthetic-registration-token' }
    }
    if ($Uri -like '*fork-pr-contributor-approval') { return @{ approval_policy = 'all_external_contributors' } }
    if ($Uri -like '*/actions/runs?*') { return @{ total_count = 0; workflow_runs = @() } }
    return @{ runners = @() }
}
try {
    foreach ($global:ciMockScenario in @('clean', 'startup-orphan', 'cleanup-failure', 'cleanup-leftover', 'early-exit')) {
        $global:ciMockRegistrations = 0
        $global:ciMockExecCalls = 0
        $global:ciMockDown = $false
        $ciRejected = $false
        try {
            if ($global:ciMockScenario -in @('cleanup-failure', 'cleanup-leftover')) {
                & (Join-Path $PSScriptRoot 'Start-LocalRunner.ps1') -Jobs 2
            } else {
                & (Join-Path $PSScriptRoot 'Start-LocalRunner.ps1')
            }
        } catch { $ciRejected = $true }
        if (($global:ciMockScenario -eq 'clean') -eq $ciRejected) { throw "Unexpected outcome: $global:ciMockScenario" }
        $ciExpectedRegistrations = if ($global:ciMockScenario -eq 'startup-orphan') { 0 } else { 1 }
        if ($global:ciMockRegistrations -ne $ciExpectedRegistrations) { throw "Unsafe registration/rotation: $global:ciMockScenario" }
        if ($global:ciMockScenario -eq 'early-exit' -and $global:ciMockExecCalls -ne 0) { throw 'Executed a command in an already exited runner.' }
        Write-Host "PASS lifecycle: $global:ciMockScenario"
    }
} finally {
    $env:GITHUB_PAT_TOKEN = $ciSavedPat
    Remove-Variable ciMockScenario, ciMockRegistrations, ciMockExecCalls, ciMockDown -Scope Global -ErrorAction SilentlyContinue
}
