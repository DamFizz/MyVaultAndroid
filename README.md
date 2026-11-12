# MyVault Android

Native Android version of MyVault (Kotlin and Jetpack Compose). It uses exactly the same vault format as the web app, so one vault opens on both.

> **Alpha.**
> - Nearly every feature of the web app is ported (see `docs/PARITY.md` for the checklist and what is still untested).
> - Google Drive sync is built but has not been tested against a real Google account yet.
> - **Not independently audited.** Keep an exported backup.

## Install (Android 8.0+)

1. Download `MyVault-<version>.apk` from the [Releases](../../releases) page on your phone.
2. Open it and allow "Install unknown apps" for your browser/file manager when Android asks.
3. In the web app go to **Backup → Export** and send the `.myvault` file to your phone.
4. In MyVault Android choose **Import a .myvault backup**, enter the backup password, then unlock with your master password (and authenticator code if you use two-step).

Later versions install over this one because every release is signed with the same key.

## What is in this build

- Liquid Glass UI: live backdrop blur (Android 12+), edge refraction and a light that follows tilt (Android 13+).
- Vault: all six item types, editor, favourites, archive, trash with undo, multi-select, views, search, sort.
- Organize categories and nested folders; password generator; security center with an opt-in breach check.
- Settings: theme, accent, language, lock and clipboard timers, vault settings, change master password,
  Authenticator with QR, recovery codes and key, Secret Key (format v2), biometric unlock.
- Backup export, merge or replace restore, safety snapshots, CSV import from other password managers.
- Google Drive sync (appDataFolder) with the same three-way merge as the web app.
- English and Bahasa Melayu.

## Project layout

- `core/` — pure Kotlin: Argon2id, HKDF, AES-GCM, vault keys, TOTP, Secret Key, unlock flow, three-way merge, backups.
- `app/` — Android app: file-based encrypted store, Keystore/biometrics, Compose UI.
- `tools/webfixture/` — compatibility tests that run the web app's own code.
- `docs/PLAN.md`, `docs/SECRET-KEY.md` — plan, compatibility proof, security limits.

## Build

Requires the Android Studio JBR (JDK 21) and Android SDK 36.

```bash
bash tools/compat-check.sh          # web <-> Kotlin compatibility (needs the web repo next to this one)
./gradlew :app:assembleRelease      # needs keystore.properties + release.jks (not in git)
```
