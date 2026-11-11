# Publish MyVault Android to GitHub: creates the repo (first time), pushes main, and uploads the
# APK from dist/ as a GitHub Release. Run from the repo root in PowerShell:
#
#   winget install --id GitHub.cli        # once
#   gh auth login                         # once, you sign in yourself
#   powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1            # private repo
#   powershell -ExecutionPolicy Bypass -File tools\publish-release.ps1 -Public    # public repo
#
# A private repo's release can only be downloaded while signed in to GitHub on the phone.
param(
    [string]$Repo = "DamFizz/MyVaultAndroid",
    [string]$Version = "0.1.0-alpha",
    [switch]$Public
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path $PSScriptRoot -Parent)

$apk = "dist\MyVault-$Version.apk"
if (-not (Test-Path $apk)) { throw "Missing $apk. Build it first: .\gradlew :app:assembleRelease" }
gh auth status | Out-Null

$exists = $true
try { gh repo view $Repo --json name | Out-Null } catch { $exists = $false }
if (-not $exists) {
    $vis = if ($Public) { "--public" } else { "--private" }
    gh repo create $Repo $vis --description "MyVault password manager - native Android app (alpha)" --source . --remote origin
}
$hasOrigin = (git remote) -contains "origin"
if (-not $hasOrigin) { git remote add origin "https://github.com/$Repo.git" }
git push -u origin main

$notes = @"
MyVault for Android - early alpha (read-only).

- Import a .myvault backup from the web app (Backup -> Export), then unlock with your master password,
  authenticator code, recovery code, recovery key, or Secret Key.
- Biometric quick unlock (Android Keystore), auto-lock, no screenshots, clipboard auto-clear.
- English and Bahasa Melayu.
- Not independently audited. Editing on Android and Google Drive sync come later.

SHA-256: $((Get-Content "$apk.sha256").Split(' ')[0])
"@
gh release create "v$Version" $apk "$apk.sha256" --repo $Repo --title "MyVault Android $Version" --notes $notes --prerelease
Write-Host "Done: https://github.com/$Repo/releases/tag/v$Version"
