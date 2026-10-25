param(
    [Parameter(Mandatory = $true)] [string] $BaselineMetadata,
    [string] $MetadataPath = (Join-Path $PSScriptRoot '..\gradle\verification-metadata.xml'),
    [Parameter(Mandatory = $true)] [string] $ReportPath
)

$ErrorActionPreference = 'Stop'

function Read-Checksums([string] $Path) {
    $document = [xml](Get-Content -LiteralPath $Path -Raw)
    foreach ($component in $document.SelectNodes('//*[local-name()="component"]')) {
        foreach ($artifact in $component.SelectNodes('./*[local-name()="artifact"]')) {
            foreach ($checksum in $artifact.SelectNodes('./*[local-name()="sha256"]')) {
                [pscustomobject]@{
                    Group = $component.GetAttribute('group')
                    Name = $component.GetAttribute('name')
                    Version = $component.GetAttribute('version')
                    Artifact = $artifact.GetAttribute('name')
                    Sha256 = $checksum.GetAttribute('value').ToLowerInvariant()
                }
            }
        }
    }
}

$baseline = @{}
foreach ($entry in Read-Checksums $BaselineMetadata) {
    $key = "$($entry.Group)|$($entry.Name)|$($entry.Version)|$($entry.Artifact)"
    if (-not $baseline.ContainsKey($key)) { $baseline[$key] = @() }
    $baseline[$key] += $entry.Sha256
}
$additions = @()
$current = @{}
foreach ($entry in Read-Checksums $MetadataPath) {
    $key = "$($entry.Group)|$($entry.Name)|$($entry.Version)|$($entry.Artifact)"
    if (-not $current.ContainsKey($key)) { $current[$key] = @() }
    $current[$key] += $entry.Sha256
    if ($baseline.ContainsKey($key)) {
        if ($entry.Sha256 -notin $baseline[$key]) {
            throw "Existing artifact gained a different hash; investigate before changing trust: $key"
        }
    } else {
        $additions += $entry
    }
}
foreach ($key in $baseline.Keys) {
    if (-not $current.ContainsKey($key) -or @($baseline[$key] | Where-Object { $_ -notin $current[$key] }).Count -gt 0) {
        throw "Existing trusted checksum was removed; investigate before changing trust: $key"
    }
}

$results = @()
foreach ($entry in $additions) {
    foreach ($part in @($entry.Group, $entry.Name, $entry.Version, $entry.Artifact)) {
        if ($part -notmatch '^[A-Za-z0-9_+.-]+$' -or $part -eq '..') {
            throw 'Unexpected Maven coordinate; refusing to build a download path.'
        }
    }
    $url = 'https://repo.maven.apache.org/maven2/' + $entry.Group.Replace('.', '/') + '/' +
        [uri]::EscapeDataString($entry.Name) + '/' + [uri]::EscapeDataString($entry.Version) + '/' +
        [uri]::EscapeDataString($entry.Artifact)
    $temporaryArtifact = [System.IO.Path]::GetTempFileName()
    try {
        # Retry only the same official URL with the platform's normal TLS and
        # certificate checks. A failed final handshake must still fail the gate.
        for ($downloadAttempt = 1; $downloadAttempt -le 3; $downloadAttempt++) {
            try {
                Invoke-WebRequest -Uri $url -OutFile $temporaryArtifact -UseBasicParsing -TimeoutSec 45 -MaximumRedirection 0
                break
            } catch {
                if ($downloadAttempt -eq 3) { throw }
                Write-Output "Retrying official download $downloadAttempt/3: $($entry.Artifact)"
                Start-Sleep -Milliseconds 500
            }
        }
        $actualHash = (Get-FileHash -LiteralPath $temporaryArtifact -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($actualHash -ne $entry.Sha256) { throw "Official artifact hash mismatch: $url" }
        $licenses = @()
        if ($entry.Artifact.EndsWith('.pom')) {
            $pom = [xml](Get-Content -LiteralPath $temporaryArtifact -Raw)
            $licenses = @($pom.SelectNodes('/*[local-name()="project"]/*[local-name()="licenses"]/*[local-name()="license"]/*[local-name()="name"]') | ForEach-Object { $_.InnerText })
            if (@($licenses | Where-Object { $_ -match 'AGPL|SSPL|Affero|Server Side Public License' }).Count -gt 0) {
                throw "Disallowed license declared by new dependency: $($entry.Group):$($entry.Name):$($entry.Version)"
            }
        }
        $results += [ordered]@{
            component = "$($entry.Group):$($entry.Name):$($entry.Version)"
            artifact = $entry.Artifact
            sha256 = $actualHash
            officialUrl = $url
            matches = $true
            declaredLicenses = $licenses
        }
        Write-Output "Verified $($entry.Artifact)"
    } finally {
        Remove-Item -LiteralPath $temporaryArtifact -ErrorAction SilentlyContinue
    }
}

$report = [ordered]@{
    verifiedAtUtc = [DateTime]::UtcNow.ToString('o')
    method = 'Independent SHA-256 of Maven Central HTTPS artifacts; no redirects; existing hashes unchanged'
    scope = 'Only additions compared with the supplied baseline. POM license declarations are recorded when present; inherited licenses require separate review.'
    artifactCount = $results.Count
    artifacts = $results
}
$reportDirectory = Split-Path -Parent ([System.IO.Path]::GetFullPath($ReportPath))
[System.IO.Directory]::CreateDirectory($reportDirectory) | Out-Null
$report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $ReportPath -Encoding UTF8
Write-Output "Verified $($results.Count) added artifacts against official Maven Central files. Report: $ReportPath"
