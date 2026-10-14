[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Python,
    [Parameter(Mandatory)][string]$Runtime,
    [Parameter(Mandatory)][string]$State,
    [Parameter(Mandatory)][string]$Repository,
    [Parameter(Mandatory)][string]$Codex,
    [Parameter(Mandatory)][string]$Manifest,
    [switch]$GitHub,
    [ValidateRange(1, 2147483647)][int]$StatusIssue,
    [ValidateRange(1, 6)][int]$MaxAgents = 6,
    [ValidateRange(0, 6)][int]$ExternalAgents = 0,
    [ValidateRange(0, 2)][int]$ExternalHeavy = 2
)
$ErrorActionPreference = 'Stop'
$pythonPath = (Resolve-Path -LiteralPath $Python).Path
$runtimePath = (Resolve-Path -LiteralPath $Runtime).Path
$repositoryPath = (Resolve-Path -LiteralPath $Repository).Path
$codexPath = (Resolve-Path -LiteralPath $Codex).Path
$manifestPath = (Resolve-Path -LiteralPath $Manifest).Path
$statePath = [IO.Path]::GetFullPath($State)
if ($statePath.StartsWith($repositoryPath.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or $statePath -eq $repositoryPath) {
    throw 'State must be outside the repository checkout.'
}
if ($runtimePath.StartsWith($repositoryPath.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase) -or $runtimePath -eq $repositoryPath) {
    throw 'Runtime must be a stable installation outside the repository checkout.'
}
if (-not (Test-Path -LiteralPath (Join-Path $runtimePath 'infra/dispatcher/cli.py'))) {
    throw 'Runtime installation is missing infra/dispatcher/cli.py.'
}
New-Item -ItemType Directory -Path $statePath -Force | Out-Null
$metadataPath = Join-Path $statePath 'process.json'
if (Test-Path -LiteralPath $metadataPath) {
    $previous = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if (Get-Process -Id $previous.pid -ErrorAction SilentlyContinue) {
        throw 'Recorded PID is still present. Reconcile it before starting another dispatcher.'
    }
}
if (Test-Path -LiteralPath (Join-Path $statePath 'STOP')) {
    throw 'STOP marker exists. Reconcile interrupted work, then explicitly remove STOP before restarting.'
}
function Quote-Argument([string]$Value) {
    if ($Value.Contains('"') -or $Value.EndsWith('\')) { throw 'Unsupported quote or trailing slash in argument.' }
    return '"' + $Value + '"'
}
$arguments = @('-B', '-m', 'infra.dispatcher.cli', '--state', (Quote-Argument $statePath), 'run', '--repository', (Quote-Argument $repositoryPath), '--codex', (Quote-Argument $codexPath), '--manifest', (Quote-Argument $manifestPath), '--max-agents', $MaxAgents, '--external-agents', $ExternalAgents, '--external-heavy', $ExternalHeavy)
if ($GitHub) { $arguments += '--github' }
if ($StatusIssue) {
    if (-not $GitHub) { throw 'StatusIssue requires GitHub reporting.' }
    $arguments += @('--status-issue', $StatusIssue)
}
$readyPath = Join-Path $statePath 'READY.json'
if (Test-Path -LiteralPath $readyPath) { Remove-Item -LiteralPath $readyPath }
$child = Start-Process -FilePath $pythonPath -ArgumentList $arguments -WorkingDirectory $runtimePath -WindowStyle Hidden -PassThru
$record = @{ pid = $child.Id; python = $pythonPath; runtime = $runtimePath; state = $statePath; start_time = $child.StartTime.ToUniversalTime().ToString('o') }
$record | ConvertTo-Json | Set-Content -LiteralPath $metadataPath -Encoding utf8
$deadline = [DateTime]::UtcNow.AddSeconds(10)
do {
    if (Test-Path -LiteralPath $readyPath) {
        try { $ready = Get-Content -LiteralPath $readyPath -Raw | ConvertFrom-Json } catch { $ready = $null }
        if ($ready -and $ready.pid -eq $child.Id -and $ready.ready -eq $true) {
            Write-Output "Dispatcher initialized PID $($child.Id). State: $statePath. It exits when no eligible work remains."
            return
        }
    }
    $child.Refresh()
    if ($child.HasExited) { throw 'Dispatcher exited before initialization confirmation. Queue retained; inspect with CLI status. No agent execution is confirmed.' }
    Start-Sleep -Milliseconds 100
} while ([DateTime]::UtcNow -lt $deadline)
throw 'Initialization confirmation timed out. Process may still run; inspect recorded identity and request graceful stop if needed. Queue retained.'
