# Publish MyVault Android to GitHub: creates the repo (first time), pushes main, and uploads the
# APK from dist/ as a GitHub Release. Run from the repo root in PowerShell:
#
#   winget install --id GitHub.cli        # once
#   gh auth login                         # once, you sign in yourself
#   powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1            # private repo
#   powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -Public    # public repo
#
# Use -Public if you want the app's built-in "Check for updates" to work: GitHub only shows a
# private repository's releases to signed-in users, and the app does not sign in to GitHub.
param(
    [string]$Repo = "DamFizz/MyVaultAndroid",
    [string]$Version = "0.2.1-alpha",
    [switch]$Public
)
# Exit codes are checked explicitly; "Stop" would turn harmless gh stderr output into fatal errors on PowerShell 5.1.
$ErrorActionPreference = "Continue"
Set-Location (Split-Path $PSScriptRoot -Parent)

$apk = "dist\MyVault-$Version.apk"
if (-not (Test-Path $apk)) { throw "Missing $apk. Build it first: .\gradlew :app:assembleRelease" }
gh auth status 2>$null | Out-Null
if ($LASTEXITCODE -ne 0) { throw "Not signed in to GitHub CLI. Run: gh auth login" }

# Native commands do not throw in PowerShell: check the exit code, not try/catch.
gh repo view $Repo --json name 2>$null | Out-Null
$exists = ($LASTEXITCODE -eq 0)
if (-not $exists) {
    $vis = if ($Public) { "--public" } else { "--private" }
    gh repo create $Repo $vis --description "MyVault password manager - native Android app (alpha)"
    if ($LASTEXITCODE -ne 0) { throw "Could not create $Repo" }
}
$hasOrigin = (git remote) -contains "origin"
if (-not $hasOrigin) { git remote add origin "https://github.com/$Repo.git" }
git push -u origin main
if ($LASTEXITCODE -ne 0) { throw "git push failed" }

$notes = @"
MyVault for Android $Version (alpha).

- Liquid Glass UI; nearly every feature of the web app (see docs/PARITY.md).
- Fingerprint / face unlock (Android Keystore, strong biometrics only), offered after the first unlock.
- Google Drive sync with the same encrypted format as the web app (needs an Android OAuth client in
  your Google Cloud project; the app shows the exact package name and SHA-1 to register).
- In-app update check from this releases page.
- Not independently audited. Keep an exported backup.

SHA-256: $((Get-Content "$apk.sha256").Split(' ')[0])
"@
gh release create "v$Version" $apk "$apk.sha256" --repo $Repo --title "MyVault Android $Version" --notes $notes --prerelease --latest
if ($LASTEXITCODE -ne 0) { throw "Release creation failed" }
Write-Host "Done: https://github.com/$Repo/releases/tag/v$Version"
