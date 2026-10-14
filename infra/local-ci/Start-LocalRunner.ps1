[CmdletBinding(DefaultParameterSetName = 'Bounded')]
param(
    [Parameter(ParameterSetName = 'Bounded')][ValidateRange(1, 10)][int]$Jobs = 1,
    [Parameter(Mandatory, ParameterSetName = 'Continuous')][switch]$Continuous,
    [ValidateSet(1, 2)][int]$Slot = 1
)
$ErrorActionPreference = 'Stop'

if ($Continuous) {
    Write-Host 'Continuous foreground mode: Ctrl+C stops and cleans the current disposable runner.'
    while ($true) {
        & $PSCommandPath -Slot $Slot
        Start-Sleep -Seconds 2
    }
}
if ($Jobs -gt 1) {
    for ($ciJob = 1; $ciJob -le $Jobs; $ciJob++) {
        Write-Host "Starting disposable runner $ciJob of $Jobs."
        & $PSCommandPath -Slot $Slot
    }
    return
}

# Run one disposable job. Invoke again for the next queued job.
# PAT stays in this host process; only a one-hour registration token is mounted.
# Keep slot 1 compatible with an already-running legacy controller.
# Each slot runs in a separate host process; environment variables are process-wide.
$ciProject = if ($Slot -eq 1) { 'armoney-ci-buildmachine' } else { 'armoney-ci-buildmachine-slot2' }
$ciMutexName = if ($Slot -eq 1) { 'ArMoneyLocalCiController' } else { 'ArMoneyLocalCiControllerSlot2' }
$ciComposeFile = Join-Path $PSScriptRoot 'compose.yaml'
$ciRunnerName = 'armoney-local-' + [guid]::NewGuid().ToString('N')
$ciTempDirectory = Join-Path ([IO.Path]::GetTempPath()) $ciRunnerName
$ciPat = [Environment]::GetEnvironmentVariable('GITHUB_PAT_TOKEN', 'Process')
if (-not $ciPat) { $ciPat = [Environment]::GetEnvironmentVariable('GITHUB_PAT_TOKEN', 'User') }
if (-not $ciPat) { throw 'Set GITHUB_PAT_TOKEN in the host environment; it is never sent to a container.' }
$ciHeaders = @{ Authorization = "Bearer $ciPat"; Accept = 'application/vnd.github+json'; 'X-GitHub-Api-Version' = '2022-11-28' }
$ciApi = 'https://api.github.com/repos/aarbabaev/ArMoney/actions/runners'
$ciOldTokenFile = $env:RUNNER_TOKEN_FILE
$ciOldRunnerName = $env:RUNNER_NAME
$ciStarted = $false
$ciExitCode = 1
$ciMutex = [Threading.Mutex]::new($false, $ciMutexName)
$ciLocked = $false
$ciCleanupFailed = $false
$ciRegistrationToken = $null
. (Join-Path $PSScriptRoot 'Runtime-Helpers.ps1')

function Invoke-CiCompose {
    param([string[]]$CiArguments)
    $ciComposeResult = Invoke-CiDocker -Arguments (@('compose', '--project-name', $ciProject, '--env-file', (Join-Path $ciTempDirectory 'empty.env'), '-f', $ciComposeFile) + $CiArguments)
    if ($ciComposeResult.ExitCode -ne 0) { throw "Local CI Compose command failed (exit $($ciComposeResult.ExitCode))." }
    $ciComposeResult.Output
}

try {
    try { $ciLocked = $ciMutex.WaitOne(0) }
    catch [Threading.AbandonedMutexException] { $ciLocked = $true }
    if (-not $ciLocked) { throw "Another local CI controller is already running in slot $Slot." }
    # A competing or dirty session in this slot must fail before any mutation.
    Assert-CiCleanRuntime -Project $ciProject
    . (Join-Path $PSScriptRoot 'Assert-RunnerTrust.ps1')
    Assert-ArMoneyRunnerTrust -Headers $ciHeaders
    New-Item -ItemType Directory -Path $ciTempDirectory | Out-Null
    if ($env:OS -eq 'Windows_NT') {
        $ciIdentity = [Security.Principal.WindowsIdentity]::GetCurrent().Name
        & icacls $ciTempDirectory /inheritance:r /grant:r "${ciIdentity}:(OI)(CI)F" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not restrict the temporary token directory ACL.' }
    }
    [IO.File]::WriteAllText((Join-Path $ciTempDirectory 'empty.env'), '')
    $ciTokenResponse = Invoke-RestMethod -Method Post -Uri "$ciApi/registration-token" -Headers $ciHeaders
    $ciRegistrationToken = $ciTokenResponse.token
    $env:RUNNER_TOKEN_FILE = Join-Path $ciTempDirectory 'registration-token'
    [IO.File]::WriteAllText($env:RUNNER_TOKEN_FILE, $ciTokenResponse.token)
    $ciTokenResponse = $null
    $env:RUNNER_NAME = $ciRunnerName
    & docker volume create armoney-ci-artifacts | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the local artifact volume.' }
    $ciStarted = $true
    Invoke-CiCompose @('up', '-d', '--no-build')
    $ciRunnerId = Invoke-CiCompose @('ps', '-aq', 'runner')
    if (-not $ciRunnerId) { throw 'Runner container was not created.' }
    $ciRegistered = $false
    for ($ciAttempt = 0; $ciAttempt -lt 60; $ciAttempt++) {
        $ciState = Invoke-CiDocker -Arguments @('inspect', '--format', '{{.State.Running}}', $ciRunnerId)
        if ($ciState.ExitCode -ne 0 -or $ciState.Output -notcontains 'true') {
            Write-Warning 'Runner container exited or became unavailable before registration. See sanitized logs below.'
            break
        }
        $ciMarker = Invoke-CiDocker -Arguments @('exec', $ciRunnerId, 'test', '-f', '/opt/runner/.runner')
        if ($ciMarker.ExitCode -eq 0) { $ciRegistered = $true; break }
        Start-Sleep -Seconds 2
    }
    # Truncate the mounted inode as well as removing it later; do not leave a token visible to jobs.
    [IO.File]::WriteAllText($env:RUNNER_TOKEN_FILE, '')
    if (-not $ciRegistered) {
        Show-CiRunnerDiagnostics -Container $ciRunnerId -Secrets @($ciPat, $ciRegistrationToken)
        throw 'Runner registration failed or timed out. Check sanitized logs and outbound GitHub Actions TLS connectivity; no job was accepted.'
    }
    Write-Host "Registered $ciRunnerName in slot $Slot ($ciProject) for one job. Artifacts remain in volume armoney-ci-artifacts."
    $ciResult = & docker wait $ciRunnerId
    if ($LASTEXITCODE -ne 0) { throw 'Could not wait for the runner.' }
    $ciExitCode = [int]$ciResult
    Show-CiRunnerDiagnostics -Container $ciRunnerId -Secrets @($ciPat, $ciRegistrationToken)
} finally {
    $ciOwnedTokenFile = Join-Path $ciTempDirectory 'registration-token'
    if (Test-Path -LiteralPath $ciOwnedTokenFile) {
        try { [IO.File]::WriteAllText($ciOwnedTokenFile, '') }
        catch { $ciCleanupFailed = $true; Write-Warning 'Could not erase the temporary registration token.' }
    }
    if ($ciStarted) {
        # Only these fixed Compose resources are removed. The external artifact volume survives.
        try {
            Invoke-CiCompose @('down', '--volumes', '--remove-orphans')
            Assert-CiCleanRuntime -Project $ciProject
        } catch { $ciCleanupFailed = $true; Write-Warning 'CI cleanup failed; follow README cleanup before restarting.' }
        # Successful ephemeral jobs deregister themselves. Interrupted registration can leave an offline entry.
        try {
            $ciRunners = Invoke-RestMethod -Uri "$ciApi`?per_page=100" -Headers $ciHeaders
            foreach ($ciRunner in $ciRunners.runners) {
                if ($ciRunner.name -eq $ciRunnerName) {
                    Invoke-RestMethod -Method Delete -Uri "$ciApi/$($ciRunner.id)" -Headers $ciHeaders | Out-Null
                }
            }
        } catch { $ciCleanupFailed = $true; Write-Warning "Could not remove stale registration $ciRunnerName; remove that exact runner in repository settings." }
    }
    if (Test-Path -LiteralPath $ciTempDirectory) {
        # Exact generated path; only known files, no recursive or calculated-tree deletion.
        try {
            foreach ($ciOwnedFile in @('registration-token', 'empty.env')) {
                $ciOwnedPath = Join-Path $ciTempDirectory $ciOwnedFile
                if (Test-Path -LiteralPath $ciOwnedPath) { Remove-Item -LiteralPath $ciOwnedPath -Force }
            }
            Remove-Item -LiteralPath $ciTempDirectory -Force
        } catch { $ciCleanupFailed = $true; Write-Warning 'Could not remove the temporary token directory.' }
    }
    $env:RUNNER_TOKEN_FILE = $ciOldTokenFile
    $env:RUNNER_NAME = $ciOldRunnerName
    $ciHeaders = $null
    $ciPat = $null
    $ciRegistrationToken = $null
    if ($ciLocked) { $ciMutex.ReleaseMutex() }
    $ciMutex.Dispose()
    if ($ciCleanupFailed) { throw 'Local CI cleanup was incomplete. Rotation stopped; manual recovery is required.' }
}
if ($ciExitCode -ne 0) { throw "Runner process exited with code $ciExitCode. Inspect the GitHub job result for test status." }
