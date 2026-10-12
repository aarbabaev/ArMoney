function Invoke-CiDocker {
    param([string[]]$Arguments)
    # Windows PowerShell 5 treats redirected native stderr as ErrorRecords.
    # Capture it without allowing an expected missing/finished container to abort diagnostics.
    $ErrorActionPreference = 'Continue'
    $ciOutput = @(& docker @Arguments 2>&1 | ForEach-Object { $_.ToString() })
    return @{ ExitCode = $LASTEXITCODE; Output = $ciOutput }
}

function Assert-CiCleanRuntime {
    param([string]$Project)
    $ciContainers = Invoke-CiDocker -Arguments @('ps', '-aq', '--filter', "label=com.docker.compose.project=$Project")
    if ($ciContainers.ExitCode -ne 0) { throw 'Cannot inspect local CI containers.' }
    if ($ciContainers.Output.Count -gt 0) { throw "Existing $Project containers found; cleanup is required." }
    $ciAllVolumes = Invoke-CiDocker -Arguments @('volume', 'ls', '-q')
    $ciLabeledVolumes = Invoke-CiDocker -Arguments @('volume', 'ls', '-q', '--filter', "label=com.docker.compose.project=$Project")
    if ($ciAllVolumes.ExitCode -ne 0 -or $ciLabeledVolumes.ExitCode -ne 0) { throw 'Cannot inspect local CI volumes.' }
    $ciLeftovers = @($ciAllVolumes.Output | Where-Object { $_.StartsWith("${Project}_") }) + @($ciLabeledVolumes.Output)
    $ciLeftovers = @($ciLeftovers | Where-Object { $_ -and $_ -ne 'armoney-ci-artifacts' })
    if ($ciLeftovers.Count -gt 0) { throw "Existing $Project volumes found; cleanup is required before registration." }
}

function Show-CiRunnerDiagnostics {
    param([string]$Container, [string[]]$Secrets)
    $ciLogs = Invoke-CiDocker -Arguments @('logs', '--tail', '40', $Container)
    if ($ciLogs.ExitCode -ne 0) { Write-Warning 'Runner logs are unavailable.'; return }
    foreach ($ciLine in $ciLogs.Output) {
        foreach ($ciSecret in $Secrets) {
            if ($ciSecret) { $ciLine = $ciLine.Replace($ciSecret, '[REDACTED]') }
        }
        Write-Host $ciLine
    }
}
