# Copies the local Android signing material into GitHub repository secrets, so tagged builds
# come out signed. Run once per repository; re-running simply overwrites the four secrets.
#
#   powershell -ExecutionPolicy Bypass -File scripts\upload-github-secrets.ps1 -Repo abaye123/MusicRadio
#
# Requires the GitHub CLI, authenticated with `gh auth login`.
param(
    [Parameter(Mandatory = $true)][string]$Repo
)

$ErrorActionPreference = 'Stop'

$root = Split-Path $PSScriptRoot -Parent
$props = Join-Path $root 'signing.local.properties'
$keystore = Join-Path $root 'release.jks'

if (-not (Test-Path $props)) { throw "Missing $props - generate the keystore first." }
if (-not (Test-Path $keystore)) { throw "Missing $keystore - generate the keystore first." }
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw 'GitHub CLI (gh) not found on PATH.' }

$values = @{}
foreach ($line in Get-Content $props) {
    $t = $line.Trim()
    if ($t -eq '' -or $t.StartsWith('#') -or -not $t.Contains('=')) { continue }
    $i = $t.IndexOf('=')
    $values[$t.Substring(0, $i).Trim()] = $t.Substring($i + 1).Trim()
}

# The keystore is binary, so it travels as base64 and the workflow decodes it back.
$b64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($keystore))

$secrets = [ordered]@{
    'ANDROID_KEYSTORE_BASE64'   = $b64
    'ANDROID_KEYSTORE_PASSWORD' = $values['ANDROID_KEYSTORE_PASSWORD']
    'ANDROID_KEY_ALIAS'         = $values['ANDROID_KEY_ALIAS']
    'ANDROID_KEY_PASSWORD'      = $values['ANDROID_KEY_PASSWORD']
}

# --body-file, not --body: passing the value as an argument would leave it in the process list,
# and `--body -` is not a stdin shorthand - it stores a literal dash.
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
foreach ($name in $secrets.Keys) {
    $value = $secrets[$name]
    if ([string]::IsNullOrWhiteSpace($value)) { throw "No value for $name in $props" }

    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("gh-secret-" + [Guid]::NewGuid().ToString('N'))
    try {
        # WriteAllText adds no trailing newline, so the secret is exactly the value.
        [System.IO.File]::WriteAllText($tmp, $value, $utf8NoBom)
        gh secret set $name --repo $Repo --body-file $tmp
        if ($LASTEXITCODE -ne 0) { throw "gh secret set $name failed" }
        Write-Output ("set {0} ({1} chars)" -f $name, $value.Length)
    } finally {
        if (Test-Path $tmp) { Remove-Item $tmp -Force }
    }
}

Write-Output ''
Write-Output 'Secrets now on the repository:'
gh secret list --repo $Repo

Write-Output ''
Write-Output 'Next: git tag v0.0.3-alpha; git push origin v0.0.3-alpha'
