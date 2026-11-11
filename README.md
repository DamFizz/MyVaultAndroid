# MyVault Android

Native Android version of MyVault (Kotlin and Jetpack Compose). It uses exactly the same vault format as the web app, so one vault opens on both.

> **Early alpha, read-only.**
> - Import a `.myvault` backup from the web app and view and copy your items.
> - Editing on Android and Google Drive sync come in later stages; see `docs/PLAN.md`.
> - **Not independently audited.**

## Install (Android 8.0+)

1. Download `MyVault-<version>.apk` from the [Releases](../../releases) page on your phone.
2. Open it and allow "Install unknown apps" for your browser/file manager when Android asks.
3. In the web app go to **Backup → Export** and send the `.myvault` file to your phone.
4. In MyVault Android choose **Import a .myvault backup**, enter the backup password, then unlock with your master password (and authenticator code if you use two-step).

Later versions install over this one because every release is signed with the same key.

## What is in this build

- Unlock with master password, the Authenticator code (with "trust this phone for 30 days"), recovery codes, or the emergency recovery key.
- Secret Key (format v2) supported when unlocking.
- Biometric quick unlock: an Android Keystore key, STRONG biometrics, reset when fingerprints change. The Authenticator code is still required unless the phone is trusted.
- Vault list with search, favourites and categories. Item details with reveal/copy. The clipboard is marked sensitive and cleared after 30 s. Protected items ask for the master password.
- Locks 1 minute after leaving the app, blocks screenshots (release builds), and is excluded from Google backups.
- English and Bahasa Melayu (follows the phone language).

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
