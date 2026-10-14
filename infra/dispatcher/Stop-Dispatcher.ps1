[CmdletBinding()]
param([Parameter(Mandatory)][string]$State)
$ErrorActionPreference = 'Stop'
$statePath = (Resolve-Path -LiteralPath $State).Path
$record = Get-Content -LiteralPath (Join-Path $statePath 'process.json') -Raw | ConvertFrom-Json
if ($record.state -ne $statePath) { throw 'Process metadata does not match requested state directory.' }
$child = Get-Process -Id $record.pid -ErrorAction SilentlyContinue
if (-not $child) { Write-Output 'Dispatcher has already exited; no process was stopped.'; return }
if ($child.StartTime.ToUniversalTime().ToString('o') -ne $record.start_time) { throw 'PID was reused; refusing to target this process.' }
$identity = Get-CimInstance Win32_Process -Filter "ProcessId = $($record.pid)"
if (-not $identity -or $identity.ExecutablePath -ne $record.python -or -not $identity.CommandLine.Contains('infra.dispatcher.cli') -or -not $identity.CommandLine.Contains($statePath)) {
    throw 'Executable or command line does not match dispatcher metadata; refusing to target this process.'
}
[IO.File]::WriteAllText((Join-Path $statePath 'STOP'), 'Graceful stop requested at ' + [DateTime]::UtcNow.ToString('o'))
Write-Output 'Graceful stop requested. Active children finish; the dispatcher checks STOP before starting further work. No process was killed and no queue or Docker resources were removed.'
