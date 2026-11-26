<#
.SYNOPSIS
    Deploy_Script: record a new deployment of a mock service in the Fake GitHub and bump the
    service's version label (Req 2.8). See mock-services/README.md for the demo sequence.

.DESCRIPTION
    1. Reads the current deployment list from the Fake GitHub (WireMock, host 127.0.0.1:8089).
    2. Adds runtime stubs through WireMock's admin API (POST /__admin/mappings). They are held in
       memory and disappear when the fake-github container restarts:
         GET /repos/{repo}/deployments                -> the new deployment first, then the earlier ones
         GET /repos/{repo}/deployments/{id}/statuses  -> one "success" status
         GET /repos/{repo}/commits/{sha}              -> the commit with the changed files
         GET /repos/{repo}/commits/{sha}/pulls        -> the merged pull request with its title
    3. Recreates the service's container with <SERVICE>_VERSION=<Version> so its OpenTelemetry
       resource attribute service.version changes. -NoRestart only prints the command.

    Changed files must not touch payment-client, timeout or retry code; the script refuses them.

.EXAMPLE
    .\mock-services\deploy.ps1

.EXAMPLE
    .\mock-services\deploy.ps1 -Service checkout-api -Version v2.14.4 -PrTitle "Fix footer copy" -PrNumber 1430 -NoRestart
#>
[CmdletBinding()]
param(
    [ValidateSet('checkout-api', 'payment-gateway', 'payment-provider')]
    [string] $Service = 'checkout-api',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._+-]{0,63}$')]
    [string] $Version = 'v2.14.3',

    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9-]{0,38}$')]
    [string] $Author = 'dev-alice',

    [ValidateLength(1, 200)]
    [string] $PrTitle = 'Update order confirmation copy',

    [ValidateRange(1, 999999)]
    [int] $PrNumber = 1423,

    # Default: two copy-only files under the service directory.
    [string[]] $ChangedFiles,

    [ValidatePattern('^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$')]
    [string] $Repository = 'moriarty-demo/checkout-platform',

    [ValidatePattern('^[A-Za-z0-9_.-]+$')]
    [string] $Environment = 'production',

    [ValidatePattern('^https?://[A-Za-z0-9.-]+(:[0-9]+)?/?$')]
    [string] $FakeGitHubUrl = 'http://127.0.0.1:8089',

    [switch] $NoRestart
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# --- Validate inputs ---------------------------------------------------------------------------

if (-not $ChangedFiles -or $ChangedFiles.Count -eq 0) {
    $ChangedFiles = @(
        "$Service/src/main/resources/messages.properties",
        "$Service/src/main/resources/templates/order-confirmation.html")
}
if ($PrTitle -match '[\x00-\x1f\x7f]') {
    throw 'PrTitle must not contain control characters.'
}
# Req 2.8: the demo deploy is harmless, so it must not touch payment-client, timeout or retry code.
$forbidden = '(?i)payment[-_]?(gateway[-_]?)?client|timeout|retr(y|ies)'
foreach ($file in $ChangedFiles) {
    if ($file -notmatch '^[A-Za-z0-9_][A-Za-z0-9_./-]{0,255}$' -or $file -match '\.\.') {
        throw "Invalid changed-file path: '$file'"
    }
    if ($file -match $forbidden) {
        throw "Changed file '$file' looks like payment-client, timeout or retry code; the demo deploy must not touch it (Req 2.8)."
    }
}

# --- Build the deployment record ---------------------------------------------------------------

$invariant = [Globalization.CultureInfo]::InvariantCulture
$isoFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
$nowOffset = [DateTimeOffset]::UtcNow
$epoch = $nowOffset.ToUnixTimeSeconds()
$createdAt = $nowOffset.UtcDateTime.ToString($isoFormat, $invariant)
$mergedAt = $nowOffset.UtcDateTime.AddMinutes(-5).ToString($isoFormat, $invariant)
$prOpenedAt = $nowOffset.UtcDateTime.AddHours(-3).ToString($isoFormat, $invariant)

$shaBytes = New-Object byte[] 20
$rng = [Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($shaBytes) } finally { $rng.Dispose() }
$sha = -join ($shaBytes | ForEach-Object { $_.ToString('x2') })

$deploymentId = $epoch
$statusId = $epoch + 1
# Links inside the bodies use the in-network URL the investigator reads (http://fake-github:8080).
$apiRepo = "http://fake-github:8080/repos/$Repository"
$webRepo = "https://github.com/$Repository"

$deployment = [ordered]@{
    url                    = "$apiRepo/deployments/$deploymentId"
    id                     = $deploymentId
    sha                    = $sha
    ref                    = "$Service-$Version"
    task                   = 'deploy'
    payload                = [ordered]@{ service = $Service; version = $Version }
    original_environment   = $Environment
    environment            = $Environment
    description            = "Deploy $Service $Version"
    creator                = [ordered]@{ login = $Author; type = 'User' }
    created_at             = $createdAt
    updated_at             = $createdAt
    statuses_url           = "$apiRepo/deployments/$deploymentId/statuses"
    repository_url         = $apiRepo
    transient_environment  = $false
    production_environment = ($Environment -eq 'production')
}

$statuses = @(
    [ordered]@{
        url            = "$apiRepo/deployments/$deploymentId/statuses/$statusId"
        id             = $statusId
        state          = 'success'
        description    = "Deployed $Service $Version"
        environment    = $Environment
        creator        = [ordered]@{ login = $Author; type = 'User' }
        created_at     = $createdAt
        updated_at     = $createdAt
        deployment_url = "$apiRepo/deployments/$deploymentId"
        repository_url = $apiRepo
    })

$files = @($ChangedFiles | ForEach-Object {
        [ordered]@{ filename = $_; status = 'modified'; additions = 3; deletions = 1; changes = 4 }
    })
$commit = [ordered]@{
    url       = "$apiRepo/commits/$sha"
    sha       = $sha
    html_url  = "$webRepo/commit/$sha"
    commit    = [ordered]@{
        message   = "$PrTitle (#$PrNumber)"
        author    = [ordered]@{ name = $Author; email = "$Author@users.noreply.github.com"; date = $mergedAt }
        committer = [ordered]@{ name = 'GitHub'; email = 'noreply@github.com'; date = $mergedAt }
    }
    author    = [ordered]@{ login = $Author; type = 'User' }
    committer = [ordered]@{ login = 'web-flow'; type = 'User' }
    parents   = @()
    stats     = [ordered]@{ total = 4 * $files.Count; additions = 3 * $files.Count; deletions = $files.Count }
    files     = $files
}

$pulls = @(
    [ordered]@{
        url              = "$apiRepo/pulls/$PrNumber"
        html_url         = "$webRepo/pull/$PrNumber"
        number           = $PrNumber
        state            = 'closed'
        title            = $PrTitle
        user             = [ordered]@{ login = $Author; type = 'User' }
        created_at       = $prOpenedAt
        merged_at        = $mergedAt
        merge_commit_sha = $sha
        head             = [ordered]@{ ref = "$Author/$Service-$Version" }
        base             = [ordered]@{ ref = 'main' }
    })

# --- Talk to the Fake GitHub -------------------------------------------------------------------

$base = $FakeGitHubUrl.TrimEnd('/')
$headers = [ordered]@{
    'Content-Type'          = 'application/json; charset=utf-8'
    'X-GitHub-Api-Version'  = '2022-11-28'
    'X-RateLimit-Limit'     = '5000'
    'X-RateLimit-Remaining' = '4999'
    'X-RateLimit-Reset'     = [string]($epoch + 3600)
}

function ConvertTo-CompactJson($Value) {
    return ConvertTo-Json -InputObject $Value -Depth 20 -Compress
}

function New-StubMapping([string] $Name, [string] $UrlPath, [string] $BodyJson) {
    # Priority 1 beats the file-based baseline (priority 5); among equal priorities WireMock
    # serves the most recently added stub, so the latest deploy wins.
    $mapping = [ordered]@{
        name     = $Name
        priority = 1
        request  = [ordered]@{ method = 'GET'; urlPath = $UrlPath }
        response = [ordered]@{ status = 200; headers = $headers; jsonBody = '__BODY__' }
        metadata = [ordered]@{ source = 'deploy-script'; service = $Service; version = $Version }
    }
    return (ConvertTo-CompactJson $mapping).Replace('"__BODY__"', $BodyJson)
}

function Add-StubMapping([string] $MappingJson) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($MappingJson)
    $null = Invoke-RestMethod -UseBasicParsing -Method Post -Uri "$base/__admin/mappings" `
        -ContentType 'application/json; charset=utf-8' -Body $bytes
}

$deploymentsPath = "/repos/$Repository/deployments"
try {
    $response = Invoke-WebRequest -UseBasicParsing -Method Get -Uri "$base$deploymentsPath" `
        -Headers @{ Accept = 'application/vnd.github+json' }
    $existing = $response.Content
    if ($existing -is [byte[]]) { $existing = [Text.Encoding]::UTF8.GetString($existing) }
} catch {
    $status = $null
    $exception = $_.Exception
    if ($exception.PSObject.Properties['Response'] -and $exception.Response) {
        $status = [int]$exception.Response.StatusCode
    }
    if ($status -eq 404) {
        $existing = '[]'
    } else {
        throw "Cannot read $base$deploymentsPath ($($exception.Message)). Is the stack up? docker compose -f deploy/docker-compose.yml up -d --build"
    }
}

$trimmed = $existing.Trim()
if (-not ($trimmed.StartsWith('[') -and $trimmed.EndsWith(']'))) {
    throw "Unexpected deployments response from the Fake GitHub (expected a JSON array)."
}
$earlier = $trimmed.Substring(1, $trimmed.Length - 2).Trim()
$deploymentJson = ConvertTo-CompactJson $deployment
$listJson = if ($earlier) { "[$deploymentJson,$earlier]" } else { "[$deploymentJson]" }
$null = ConvertFrom-Json -InputObject $listJson   # fail before posting if the splice is not valid JSON

# Commit, PR and status stubs first, so the list never names a sha the fake cannot serve.
Add-StubMapping (New-StubMapping "GET commit $sha ($Service $Version)" "/repos/$Repository/commits/$sha" (ConvertTo-CompactJson $commit))
Add-StubMapping (New-StubMapping "GET commit pulls $sha ($Service $Version)" "/repos/$Repository/commits/$sha/pulls" (ConvertTo-CompactJson $pulls))
Add-StubMapping (New-StubMapping "GET deployment statuses $deploymentId ($Service $Version)" "$deploymentsPath/$deploymentId/statuses" (ConvertTo-CompactJson $statuses))
Add-StubMapping (New-StubMapping "GET deployments: $Service $Version" $deploymentsPath $listJson)

Write-Host "Recorded deployment $deploymentId of $Service $Version at $createdAt (sha $sha, PR #$PrNumber by $Author)."
Write-Host "Check: Invoke-RestMethod $base$deploymentsPath"

# --- Bump the version label --------------------------------------------------------------------

$versionVar = ($Service.ToUpperInvariant() -replace '-', '_') + '_VERSION'
$composeService = "mock-$Service"
$composeFile = (Resolve-Path (Join-Path $PSScriptRoot '..\deploy\docker-compose.yml')).Path
$manual = "`$env:$versionVar = '$Version'; docker compose -f deploy/docker-compose.yml up -d --no-deps --no-build $composeService"

if ($NoRestart) {
    Write-Host "Version label not changed (-NoRestart). To apply it, run from the repository root:"
    Write-Host "  $manual"
    return
}

Write-Host "Recreating $composeService with $versionVar=$Version ..."
$previousValue = [Environment]::GetEnvironmentVariable($versionVar, 'Process')
$previousPreference = $ErrorActionPreference
try {
    [Environment]::SetEnvironmentVariable($versionVar, $Version, 'Process')
    # docker writes progress to stderr; do not let PowerShell treat that as a terminating error.
    $ErrorActionPreference = 'Continue'
    & docker compose -f $composeFile up -d --no-deps --no-build $composeService
    $exitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousPreference
    [Environment]::SetEnvironmentVariable($versionVar, $previousValue, 'Process')
}
if ($exitCode -ne 0) {
    throw "docker compose exited with code $exitCode. The deployment is recorded; apply the label manually: $manual"
}
Write-Host "$composeService now reports service.version=$Version. A later plain 'docker compose up' resets it unless $versionVar is set."
