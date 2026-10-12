function Assert-ArMoneyRunnerTrust {
    [CmdletBinding()]
    param([Parameter(Mandatory)][hashtable]$Headers)
    $ciRepositoryApi = 'https://api.github.com/repos/aarbabaev/ArMoney'
    $ciPolicy = Invoke-RestMethod -Uri "$ciRepositoryApi/actions/permissions/fork-pr-contributor-approval" -Headers $Headers -ErrorAction Stop
    if ($ciPolicy.approval_policy -ne 'all_external_contributors') {
        throw 'Runner registration requires fork approval policy all_external_contributors.'
    }
    # Include workflows waiting for approval. Missing identities fail closed.
    foreach ($ciStatus in @('queued', 'in_progress', 'waiting', 'requested', 'pending')) {
        $ciSeen = 0
        for ($ciPage = 1; $ciPage -le 10; $ciPage++) {
            $ciRuns = Invoke-RestMethod -Uri "$ciRepositoryApi/actions/runs?status=$ciStatus&per_page=100&page=$ciPage" -Headers $Headers -ErrorAction Stop
            if ($null -eq $ciRuns.total_count -or $null -eq $ciRuns.workflow_runs -or $ciRuns.total_count -gt 1000) {
                throw 'Cannot completely audit active workflow runs; refusing runner registration.'
            }
            foreach ($ciRun in $ciRuns.workflow_runs) {
                if ($ciRun.actor.login -ne 'aarbabaev' -or
                    $ciRun.triggering_actor.login -ne 'aarbabaev' -or
                    $ciRun.repository.full_name -ne 'aarbabaev/ArMoney' -or
                    $ciRun.head_repository.full_name -ne 'aarbabaev/ArMoney') {
                    throw "Active workflow run $($ciRun.id) has an external or missing actor/repository; refusing runner registration."
                }
                if ($ciRun.event -notin @('push', 'pull_request', 'workflow_dispatch')) {
                    throw "Active workflow run $($ciRun.id) has an unapproved event type; refusing runner registration."
                }
            }
            $ciSeen += @($ciRuns.workflow_runs).Count
            if (@($ciRuns.workflow_runs).Count -lt 100) {
                if ($ciSeen -lt $ciRuns.total_count) { throw 'Incomplete active-run audit; refusing runner registration.' }
                break
            }
            if ($ciPage -eq 10 -and $ciSeen -lt $ciRuns.total_count) { throw 'Active-run audit exceeded its bound.' }
        }
    }
    Write-Host 'Trust preflight passed: external contributors require approval; active runs have owner identities and this repository.'
}
