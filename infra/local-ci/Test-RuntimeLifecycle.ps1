# Host-only regression: all Docker and GitHub calls are mocked; no stack is touched.
# Relocate only mutex names so tests cannot acquire or contend with live controllers.
$ErrorActionPreference = 'Stop'
$ciSavedPat = $env:GITHUB_PAT_TOKEN
$ciSavedTokenFile = $env:RUNNER_TOKEN_FILE
$ciSavedRunnerName = $env:RUNNER_NAME
$env:GITHUB_PAT_TOKEN = 'synthetic-pat-for-regression'
$ciFixture = Join-Path ([IO.Path]::GetTempPath()) ('armoney-ci-lifecycle-' + [guid]::NewGuid().ToString('N'))
$ciMutexPrefix = 'ArMoneyCiTest' + [guid]::NewGuid().ToString('N')
$ciProjects = @{ 1 = 'armoney-ci-buildmachine'; 2 = 'armoney-ci-buildmachine-slot2' }

function Reset-CiMock {
    param([string]$Scenario, [int]$Slot)
    $global:ciMockScenario = $Scenario
    $global:ciMockProject = $ciProjects[$Slot]
    $global:ciMockOtherProject = $ciProjects[3 - $Slot]
    $global:ciMockRegistrations = 0
    $global:ciMockExecCalls = 0
    $global:ciMockSleeps = 0
    $global:ciMockDown = $false
    $global:ciMockDockerCalls = [Collections.Generic.List[string]]::new()
    $global:ciMockApiCalls = [Collections.Generic.List[string]]::new()
}
function docker {
    $global:LASTEXITCODE = 0
    $ciCommand = $args -join ' '
    $global:ciMockDockerCalls.Add($ciCommand)
    if ($ciCommand -eq 'volume ls -q') {
        'armoney-ci-artifacts'
        # The other slot always has live data; its presence must not block this slot.
        "${global:ciMockOtherProject}_docker-data"
        if ($global:ciMockScenario -eq 'startup-orphan' -or ($global:ciMockScenario -eq 'cleanup-leftover' -and $global:ciMockDown)) {
            "${global:ciMockProject}_docker-data"
        }
    } elseif ($ciCommand -like 'ps -aq *' -or $ciCommand -like 'volume ls -q --filter *') {
        if ($ciCommand -like "*=$global:ciMockOtherProject") { 'other-slot-resource' }
        if ($global:ciMockScenario -eq 'startup-container' -and $ciCommand -like 'ps *') { 'dirty-slot-container' }
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
        if ((Get-Item -LiteralPath $env:RUNNER_TOKEN_FILE).Length -ne 0) { throw 'Token was not erased before waiting for the job.' }
        '0'
    } elseif ($ciCommand -like 'logs *') {
        'Synthetic TLS connection EOF; token synthetic-registration-token and synthetic-pat-for-regression must be redacted.'
    }
}
function Invoke-RestMethod {
    param($Method, $Uri, $Headers, $ErrorAction)
    $global:ciMockApiCalls.Add("$Method $Uri")
    if ($Uri -like '*/registration-token') {
        $global:ciMockRegistrations++
        return @{ token = 'synthetic-registration-token' }
    }
    if ($Uri -like '*fork-pr-contributor-approval') {
        $ciPolicy = if ($global:ciMockScenario -eq 'untrusted') { 'first_time_contributors' } else { 'all_external_contributors' }
        return @{ approval_policy = $ciPolicy }
    }
    if ($Uri -like '*/actions/runs?*') { return @{ total_count = 0; workflow_runs = @() } }
    if ($Method -eq 'Delete') { return }
    return @{ runners = @(@{ name = $env:RUNNER_NAME; id = 101 }, @{ name = 'other-slot-runner'; id = 202 }) }
}
function Start-Sleep {
    param($Seconds)
    if ($global:ciMockScenario -ne 'continuous') { throw 'Unexpected polling sleep in lifecycle fixture.' }
    $global:ciMockSleeps++
    if ($global:ciMockSleeps -eq 2) { throw 'Synthetic continuous stop after two jobs.' }
}
function Assert-CiSlotCalls {
    param([int]$Jobs)
    $ciComposeCalls = @($global:ciMockDockerCalls | Where-Object { $_ -like 'compose *' })
    foreach ($ciCall in $ciComposeCalls) {
        if ($ciCall -notlike "compose --project-name $global:ciMockProject --env-file *") { throw "Wrong slot Compose target: $ciCall" }
    }
    if (@($ciComposeCalls | Where-Object { $_ -like '* down --volumes --remove-orphans' }).Count -ne $Jobs) { throw 'Unexpected cleanup count.' }
    foreach ($ciCall in @($global:ciMockDockerCalls | Where-Object { $_ -like '*--filter*' })) {
        if ($ciCall -notlike "*label=com.docker.compose.project=$global:ciMockProject") { throw "Wrong slot inspection: $ciCall" }
    }
    if (@($global:ciMockApiCalls | Where-Object { $_ -like 'Delete */202' }).Count -ne 0) { throw 'Deleted another runner registration.' }
    if (@($global:ciMockApiCalls | Where-Object { $_ -like 'Delete */101' }).Count -ne $Jobs) { throw 'Expected exact-name registration cleanup.' }
    if ($env:RUNNER_TOKEN_FILE -ne $ciSavedTokenFile -or $env:RUNNER_NAME -ne $ciSavedRunnerName) { throw 'Controller did not restore process environment.' }
}
try {
    New-Item -ItemType Directory -Path $ciFixture | Out-Null
    $ciSource = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'Start-LocalRunner.ps1'))
    foreach ($ciName in @('ArMoneyLocalCiController', 'ArMoneyLocalCiControllerSlot2')) {
        if (-not $ciSource.Contains("'$ciName'")) { throw "Production mutex contract changed: $ciName" }
    }
    $ciSource = $ciSource.Replace("'ArMoneyLocalCiController'", "'${ciMutexPrefix}Slot1'").Replace("'ArMoneyLocalCiControllerSlot2'", "'${ciMutexPrefix}Slot2'")
    $ciController = Join-Path $ciFixture 'Start-LocalRunner.ps1'
    [IO.File]::WriteAllText($ciController, $ciSource)
    foreach ($ciFile in @('Runtime-Helpers.ps1', 'Assert-RunnerTrust.ps1', 'compose.yaml')) {
        Copy-Item -LiteralPath (Join-Path $PSScriptRoot $ciFile) -Destination (Join-Path $ciFixture $ciFile)
    }
    foreach ($ciSlot in @(1, 2)) {
        foreach ($ciScenario in @('clean', 'startup-orphan', 'startup-container', 'cleanup-failure', 'cleanup-leftover', 'early-exit', 'untrusted')) {
            Reset-CiMock $ciScenario $ciSlot
            $ciRejected = $false
            $ciOutput = @()
            try {
                $ciJobs = if ($ciScenario -in @('cleanup-failure', 'cleanup-leftover')) { 2 } else { 1 }
                $ciOutput = @(& $ciController -Slot $ciSlot -Jobs $ciJobs 6>&1)
            } catch { $ciRejected = $true }
            if (($ciScenario -eq 'clean') -eq $ciRejected) { throw "Unexpected outcome: slot $ciSlot $ciScenario" }
            $ciExpectedRegistrations = if ($ciScenario -in @('startup-orphan', 'startup-container', 'untrusted')) { 0 } else { 1 }
            if ($global:ciMockRegistrations -ne $ciExpectedRegistrations) { throw "Unsafe registration/rotation: slot $ciSlot $ciScenario" }
            if ($ciScenario -eq 'early-exit' -and $global:ciMockExecCalls -ne 0) { throw 'Executed a command in an already exited runner.' }
            if (($ciOutput -join "`n") -match 'synthetic-registration-token|synthetic-pat-for-regression') { throw 'Diagnostics exposed a secret.' }
            Assert-CiSlotCalls $ciExpectedRegistrations
            Write-Host "PASS lifecycle: slot $ciSlot $ciScenario"
        }
        foreach ($ciMode in @('bounded', 'continuous')) {
            Reset-CiMock $ciMode $ciSlot
            if ($ciMode -eq 'bounded') {
                & $ciController -Slot $ciSlot -Jobs 2
            } else {
                $ciStopped = $false
                try { & $ciController -Slot $ciSlot -Continuous }
                catch {
                    if ($_.Exception.Message -ne 'Synthetic continuous stop after two jobs.') { throw }
                    $ciStopped = $true
                }
                if (-not $ciStopped) { throw 'Continuous fixture did not stop.' }
            }
            if ($global:ciMockRegistrations -ne 2) { throw "Slot not forwarded in $ciMode mode." }
            Assert-CiSlotCalls 2
            Write-Host "PASS rotation: slot $ciSlot $ciMode"
        }
    }
    foreach ($ciInvalid in @(0, 3, 'main')) {
        Reset-CiMock 'invalid-slot' 1
        $ciRejected = $false
        try { & $ciController -Slot $ciInvalid } catch { $ciRejected = $true }
        if (-not $ciRejected -or $global:ciMockDockerCalls.Count -ne 0 -or $global:ciMockApiCalls.Count -ne 0) { throw "Invalid slot accepted: $ciInvalid" }
        Write-Host "PASS invalid slot: $ciInvalid"
    }
    # Hold each test mutex on a different thread: same-thread mutexes are reentrant.
    # This holder executes no controller, environment mutation, Docker or GitHub call.
    foreach ($ciSlot in @(1, 2)) {
        $ciReady = [Threading.ManualResetEventSlim]::new($false)
        $ciRelease = [Threading.ManualResetEventSlim]::new($false)
        $ciHolder = [PowerShell]::Create()
        $null = $ciHolder.AddScript({
            param($Name, $Ready, $Release)
            $ciHeldMutex = [Threading.Mutex]::new($false, $Name)
            $ciHeld = $false
            try {
                $ciHeld = $ciHeldMutex.WaitOne(5000)
                if (-not $ciHeld) { throw 'Could not hold fixture mutex.' }
                $Ready.Set()
                if (-not $Release.Wait(30000)) { throw 'Fixture release timed out.' }
            } finally {
                if ($ciHeld) { $ciHeldMutex.ReleaseMutex() }
                $ciHeldMutex.Dispose()
            }
        }).AddArgument("${ciMutexPrefix}Slot$ciSlot").AddArgument($ciReady).AddArgument($ciRelease)
        $ciHandle = $ciHolder.BeginInvoke()
        try {
            if (-not $ciReady.Wait(5000)) { throw 'Fixture mutex was not acquired.' }
            Reset-CiMock 'same-slot' $ciSlot
            $ciRejected = $false
            try { & $ciController -Slot $ciSlot }
            catch {
                if ($_.Exception.Message -ne "Another local CI controller is already running in slot $ciSlot.") { throw }
                $ciRejected = $true
            }
            if (-not $ciRejected -or $global:ciMockDockerCalls.Count -ne 0 -or $global:ciMockApiCalls.Count -ne 0) { throw 'Same-slot contention did not fail before external calls.' }
            # The other slot must still register, run and clean its own resources.
            Reset-CiMock 'other-slot' (3 - $ciSlot)
            & $ciController -Slot (3 - $ciSlot)
            Assert-CiSlotCalls 1
            Write-Host "PASS mutex: slot $ciSlot rejected; other slot completed"
        } finally {
            $ciRelease.Set()
            $null = $ciHolder.EndInvoke($ciHandle)
            $ciHolder.Dispose()
            $ciReady.Dispose()
            $ciRelease.Dispose()
        }
    }
    Reset-CiMock 'default-slot' 1
    & $ciController
    Assert-CiSlotCalls 1
    Write-Host 'PASS default slot: legacy project and mutex preserved'
} finally {
    $env:GITHUB_PAT_TOKEN = $ciSavedPat
    $env:RUNNER_TOKEN_FILE = $ciSavedTokenFile
    $env:RUNNER_NAME = $ciSavedRunnerName
    if (Test-Path -LiteralPath $ciFixture) {
        foreach ($ciFile in @('Start-LocalRunner.ps1', 'Runtime-Helpers.ps1', 'Assert-RunnerTrust.ps1', 'compose.yaml')) {
            $ciPath = Join-Path $ciFixture $ciFile
            if (Test-Path -LiteralPath $ciPath) { Remove-Item -LiteralPath $ciPath -Force }
        }
        Remove-Item -LiteralPath $ciFixture -Force
    }
    Remove-Variable ciMockScenario, ciMockProject, ciMockOtherProject, ciMockRegistrations, ciMockExecCalls, ciMockSleeps, ciMockDown, ciMockDockerCalls, ciMockApiCalls -Scope Global -ErrorAction SilentlyContinue
}
