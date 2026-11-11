# MyVault Android: plan

The web app (`D:\DAMMYVAULT`, live at myvault-damfizz.web.app) is the reference implementation, and it stays unchanged. The Android app has to read and write exactly the same vault format, so one vault works on both through the Drive `appDataFolder` file `myvault.vault.json`.

## Status

| Stage | Status |
|---|---|
| 1. Toolchain | ✅ Done. JBR 21 from Android Studio, SDK platform 36, build-tools 35/36.1, Gradle 8.14.3 wrapper |
| 2. Core crypto + vault format (`:core`, pure Kotlin) | ✅ Done. 18 Kotlin tests and 8 web-side tests pass |
| 3. Local storage, unlock, biometrics | ⏳ Planned (below) |
| 4. Compose UI with Liquid Glass | ⏳ Planned |
| 5. Drive sync and Autofill | ⏳ Planned |

## Stage 2: what exists and how compatibility is proven

The `:core` module (`core/src/main/kotlin/com/damfizz/myvault/core`) has no Android dependencies. It uses only Bouncy Castle (Argon2id) and kotlinx.serialization:

| File | Ported from | Contents |
|---|---|---|
| `Crypto.kt` | `src/lib/crypto.ts` | Argon2id (64 MiB, t=3, p=1, v1.3, NFC-normalised password), HKDF-SHA-256 (UTF-8 salt/info, as Web Crypto does it), AES-256-GCM with 96-bit IV, 128-bit tag and AAD |
| `Keys.kt` | `src/vault/keys.ts` | AAD strings, subkeys (records/security/local), device-trust HMAC key, password/secret KEK, wrap/unwrap, recovery key (160 bits, base32), recovery codes and their hashes |
| `Totp.kt` | `src/lib/totp.ts` | RFC 6238, ±1 step window, replay protection, clock-drift detection, otpauth URI |
| `Model.kt` | `src/vault/types.ts`, `src/sync/format.ts` | Header, records, security config, items/collections/settings, sync-file parse/validate/serialize |
| `Session.kt` | `src/vault/session.ts` | Decrypt and seal records, commit header, device secrets |
| `Access.kt` | `src/vault/access.ts` | Create vault, unlock with password/TOTP/recovery code/recovery key, trusted device, change password, enable/disable TOTP, throttle |
| `Merge.kt` | `src/sync/merge.ts` | Three-way merge |
| `Backup.kt` | `src/vault/backup.ts` | `.myvault` backup files |
| `Store.kt` | — | `VaultStore` interface (header/records/local, same as the Dexie tables), in-memory implementation, throttle, device id |

**Data preservation.** Item, collection and settings payloads keep their original JSON (`raw`). When Android edits an item, fields Android does not know about (written by a newer web version) and explicit `null`s are kept. A test proves this with `futureField`.

**Compatibility proof in both directions.** The web side runs on the web repo's own vitest and its own `src/` modules. Nothing in `D:\DAMMYVAULT` is modified.

1. **Web → Kotlin.** `tools/webfixture/gen-web.fixture.ts` uses the web modules to create a vault with:
   - an NFD password and emoji
   - TOTP, a recovery key, 10 recovery codes and a 30-day trust token
   - a tombstone, nested sections, settings, a `futureField`, and a record at rev 2
   - a backup
   - vectors: Argon2id at the default 64 MiB cost, HKDF and TOTP

   The output goes to `core/src/test/resources/fixtures/web-vault.json`. `WebToKotlinTest` opens it and checks that:
   - every record decrypts to the same JSON
   - the trust token is accepted on the same device and rejected on another
   - the TOTP code works and the same code is refused a second time (replay)
   - recovery codes work once
   - the recovery key resets the password and still asks for TOTP
   - the backup opens
   - the vectors match
2. **Kotlin → web.** `KotlinToWebTest` writes `tools/webfixture/out/kotlin-vault.json` containing:
   - (a) a vault created entirely by Kotlin
   - (b) the web vault after Android edited it: item edits, purge, new collection, new recovery codes, new master password

   `verify-kotlin.fixture.ts` checks it with the web modules. Everything has to unlock and decrypt identically, and `futureField` has to survive.
3. **Negative control.** When the `rev` of one record was tampered with, the web verifier failed as expected, because the AAD binds id/rev/vtag.

Run everything with: `bash tools/compat-check.sh`

## Stage 3: local storage, unlock, biometrics

- **Module `:app`.** Android application module: AGP 8.13 (already cached), Kotlin 2.2, Compose BOM, minSdk 26, targetSdk 36, `applicationId com.damfizz.myvault`.
- **Storage.** Use Room (SQLite) with tables `meta`, `records`, `local` and `snapshots`, the same as the Dexie schema. `RoomVaultStore` implements `VaultStore`. `replaceVault` runs in one transaction. Everything except `local` is already ciphertext.
  - `local` holds throttle, `totpLastStep`, `totpTrust`, `sync:*`, `device`, and `secrets:*` (sealed with the local subkey).
  - `android:allowBackup="false"` plus `dataExtractionRules`, so the trust token and throttle are not restored onto another phone.
- **Unlock flow.** A `VaultViewModel` runs Argon2 on `Dispatchers.Default`. The UI states match the web: password → (TOTP | recovery code) → unlocked, plus a "trust this device for 30 days" option. The second factor expires after 3 minutes, as on the web.
- **Argon2 performance.** Bouncy Castle is pure Java. Measure it on the user's phone, target ≤ 1.5 s. If it is too slow, switch to native Argon2 via JNI. The vector test guarantees the output stays identical. Watch memory: 64 MiB is allocated once per unlock.
- **Biometric quick unlock (Android-only, device-local).**
  - Keystore AES-256-GCM key with `setUserAuthenticationRequired(true)` and per-use auth (BiometricPrompt + `CryptoObject`).
  - `setInvalidatedByBiometricEnrollment(true)`, and StrongBox when the phone has it.
  - The key wraps the raw vault key. The wrap is stored in `local` as `quickUnlock:<vaultId>`.
  - After biometric auth the app calls `unlockWithUnwrappedKey`. TOTP is still enforced unless the device is trusted, as with the web passkey.
  - Changing the master password does not change the vault key, so the wrap stays valid. Removing quick unlock deletes the Keystore key.
- **Protection.**
  - `FLAG_SECURE` on sensitive screens.
  - The clipboard is marked `EXTRA_IS_SENSITIVE` (Android 13+) and cleared automatically.
  - Auto-lock when the app goes to the background, with a timer like the web's `autolock.ts`.
  - The vault-key wrap is erased on lock.

## Stage 4: Compose UI with Liquid Glass

- **Glass layer** (one shared `Modifier.liquidGlass()`):
  - **API 33+:** AGSL `RuntimeShader`:
    - edge refraction (displacement by the rounded-rectangle SDF, like `useRefraction` in `liquid.tsx`)
    - a specular highlight that follows the touch point and tilt (sensor, optional)
    - light chromatic aberration
  - **API 31–32:** backdrop blur through `RenderEffect` with a `GraphicsLayer` recording of the background (Compose 1.7+), plus a gradient highlight. The open-source *Haze* library (Apache-2.0) is an option instead of building it ourselves.
  - **API 26–30:** translucent gradient + noise, no blur.
- **Motion, following `liquid.tsx` / `index.css`:**
  - Press swell: a spring scale that follows the press point.
  - "Flowing" selection pill (stretch then a damped settle, `spring(dampingRatio≈0.55)`).
  - Popups grow from the press point (`aimPopupAtPress`).
  - Pointer light.
  - All of it uses `Animatable`/`spring` without recomposition every frame (use `graphicsLayer {}` lambdas).
- **Screens:**
  - vault list (search, favourites, categories/folders sidebar like the web)
  - item detail/editor for all 6 item types, including reprompt
  - generator (port of `src/lib/generator.ts`, `strength.ts`)
  - Security Center (`security-analysis.ts`, HIBP k-anonymity)
  - settings, backup/restore
- **Language.** `values/strings.xml` (en) and `values-ms/strings.xml` (ms), ported from `src/i18n/en.ts` / `ms.ts`. Malay is the default if the user picks it.
- **Smoothness.** Measure, don't guess:
  - Macrobenchmark (scroll of 500 items, opening an item) and JankStats.
  - Baseline Profile.
  - Target: 0 janky frames on the user's phone. Limit how many blurred layers are on screen at once.

## Stage 5: Drive sync and Autofill

- **Sync.**
  - Port `src/sync/engine.ts` to `core` with a `RemoteStore` interface, so it can be tested offline with an in-memory remote:
    - same `SyncState` (`sync:<vaultId>`)
    - optimistic concurrency on the file version
    - conflict copies
    - "edit beats delete"
    - `maxHeaderRev` rollback protection
    - `VaultMismatchError` / `RemoteKeyMismatchError`
  - Port the `sync.test.ts` tests as well.
  - Add cross-tests: the web engine and the Kotlin engine syncing the same remote file alternately.
- **Drive.**
  - REST v3 over OkHttp, `spaces=appDataFolder`, scope `drive.appdata`.
  - Login goes through Credential Manager + `AuthorizationClient` (Google Identity).
  - **You (the user) have to create an Android OAuth client** in the same Google Cloud project as the web app (package name + SHA-1 of the signing key). Claude will not log in on your behalf.
  - Background sync runs through WorkManager, only while the vault is unlocked (the same rule as the web).
- **Autofill.**
  - `AutofillService` reads the `AssistStructure` (username/password fields) and matches by package name / web domain (public suffix).
  - When the vault is locked, the dataset requires authentication: an unlock activity opens and then returns the dataset.
  - `SaveRequest` for new passwords. Inline suggestions on the keyboard (API 30+). Items with reprompt ask for the master password.

## Honest security limits (not audited)

- **Not audited.** Neither this app nor the web app has had an independent security audit.
- **TOTP is app-enforced, not cryptographic.** The TOTP secret sits inside the encrypted security config. Anyone who has both the master password and the vault file can decrypt it offline without the TOTP code. This is the same as the web version. 2FA protects the app's unlock flow, not the ciphertext.
- **Memory on the JVM/ART.** `String` (the master password from a text field) and the copies `SecretKeySpec` makes cannot be wiped reliably. `wipe()` is best-effort only.
- **Throttling only slows guessing through the app.** Offline attacks are slowed by Argon2id alone.
- **Device risks.** A rooted phone, malware with Accessibility access, or a screen recorder can read what is on screen. `FLAG_SECURE` and auto-lock reduce the risk but do not remove it.
- **Bouncy Castle Argon2 is pure Java.** Its speed on a phone has not been measured yet (stage 3).
