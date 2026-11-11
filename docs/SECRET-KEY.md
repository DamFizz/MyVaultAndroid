# Design: Secret Key (vault format v2)

Status: **approved** (all 5 decisions as recommended). **Step 1 of the rollout (format support, no UI) is done:**
- Web: branch `secret-key-format` (worktree `D:\DAMMYVAULT-secretkey`). Changed `src/vault/{keys,access,types,import-keys,backup}.ts` and `src/sync/{format,engine}.ts`. New: `src/vault/secret-key.ts`, `tests/secret-key.test.ts` (11 tests). The full suite passes 105/105 and `tsc -b` is clean.
- Android `:core`: `SecretKey.kt` plus `Access`/`Model`/`Crypto`/`Keys`. `SecretKeyTest` has 6 tests, and the cross-fixtures cover v2 in both directions.
- Negative control: removing the no-downgrade rule makes the concurrent-sync test fail.
- Not done yet: the UI (Emergency Kit, QR, Settings → Security), deploying the web, and the Kotlin sync engine (stage 5 must port the no-downgrade rule).

## 1. Purpose

Today an attacker who holds **the vault file** (from Google Drive, a stolen backup or a copy of the browser's IndexedDB) **and the master password** can decrypt everything offline. The Authenticator code does not stop them, because the TOTP secret sits inside the vault itself. A weak password can also be guessed offline, slowed down only by Argon2id.

The Secret Key closes that gap. It is a random 128-bit key that is **never stored in the cloud file**. It is stored only on your devices and in a printed Emergency Kit. Opening the vault needs **the master password + the Secret Key**.

| What the attacker has | Today (v1) | With Secret Key (v2) |
|---|---|---|
| Drive file only | Guess the password offline (Argon2id) | ❌ Impossible (128 bits + password) |
| Drive file + master password (e.g. phished) | ✅ All data opens | ❌ Still needs the Secret Key |
| Drive file + Secret Key (e.g. kit lost) | Guess the password | Guess the password (same as today, no worse) |
| Drive file + recovery key | ✅ Opens (by design) | ✅ Opens (by design, unchanged) |
| An unlocked device / malware on the device | ✅ | ✅ (the Secret Key does not protect a compromised device) |
| Your device + master password | ✅ (TOTP enforced by the app) | ✅ (the device already holds the Secret Key) |

So the Secret Key protects your **cloud copy and backups**. It does not protect a device an attacker already controls.

## 2. Cryptography

### 2.1 The Secret Key

- `sk` = 16 random bytes (CSPRNG).
- **Display format:** `MV2-` followed by 28 base32 characters in groups of 4. Those 28 characters are 26 characters of `sk` plus 2 checksum characters, where `checksum` = the first 10 bits of `SHA-256("myvault/v2/secret-key-check/" ‖ sk)`. Example: `MV2-ABCD-EFGH-IJKL-MNOP-QRST-UVWX-YZ23`. The checksum catches typos before Argon2 runs, so a mistyped key costs no extra time.
- **QR:** `myvault-sk:2:<vaultId>:<26 characters of sk>`.

### 2.2 KEK v2 (replaces `passwordKek` when the Secret Key is on)

```
stretched = Argon2id(NFC(password), kdf.salt, 64 MiB, t=3, p=1)              // unchanged
kek       = HKDF-SHA-256(ikm  = sk,
                         salt = stretched,
                         info = "myvault/v2/kek/password+secret-key/" ‖ vaultId) → AES-256-GCM
passwordWrap = AES-GCM(kek, VK, AAD = "myvault/v1/wrap/password-sk/" ‖ vaultId)
```

- **Why this shape:**
  - The HKDF-Extract step computes `HMAC(stretched, sk)`. If `sk` leaks, what remains is still the Argon2 cost of the password. If the password leaks, the attacker has to guess 128 bits.
  - On the web, `sk` can be held as a **non-extractable** `CryptoKey` (HKDF base key), with `stretched` passed as the salt. JavaScript never needs the raw bytes after setup.
- The **new info string and AAD purpose** keep v1 and v2 completely separate. A v1 wrap can never be confused with a v2 wrap.
- The vault key (VK), the subkeys, the records and the item ciphertexts **do not change at all**. Only `passwordWrap` is re-wrapped.

### 2.3 Secret Key identifier (better error messages)

`skId = base64url(HKDF(sk, info="myvault/v2/secret-key-id", salt=vaultId, 9 bytes))` → 12 characters, stored in the header in plaintext.

- With it the app can say **"wrong Secret Key"** instead of "wrong password", and it does not ask for a key from another vault.
- It reveals nothing about the password. It is only a verifier for a 128-bit `sk`, so it cannot be brute-forced.

## 3. Format changes

### 3.1 Header (synced, plaintext but authenticated)

```jsonc
{
  "format": "myvault-vault",
  "version": 2,                       // ← 1 → 2 once the Secret Key has ever been turned on
  "secretKey": { "id": "Xy3…", "createdAt": 1794… } | null,   // ← new; null = password only (v1 KEK)
  "passwordWrap": { … },              // wrapped with KEK v2 when secretKey != null
  // other fields unchanged
}
```

### 3.2 SecurityConfig (encrypted with the security subkey)

```jsonc
{ …, "secretKey": "<26 base32 characters>" | null }
```

The Secret Key is also kept **encrypted inside the vault**. Any device that is already unlocked can show it again (QR or Emergency Kit). Reading it requires the VK, which in turn needs password + SK, or the recovery key. That keeps the flows in §4 possible without storing the key anywhere in plaintext.

### 3.3 Device storage (`local`, never synced)

| Platform | Key | Value |
|---|---|---|
| Web | `secretKey:<vaultId>` | `{ id, key: CryptoKey }`, HKDF, **non-extractable**, stored in IndexedDB |
| Android | `secretKey:<vaultId>` | `sk` sealed with an Android Keystore AES key (no biometric needed). It cannot be read from a file copy or adb backup |

There is a **"Don't remember on this device"** option for shared PCs. With it, the Secret Key is asked for on every unlock.

### 3.4 Old versions

v1 apps (the web before the update, Android stage 2) already reject `version > 1` with "Unsupported vault format version". **They stop syncing and never overwrite a v2 vault.** That is safe, but they need updating. The web PWA updates itself automatically.

## 4. User flows

### 4.1 Turning the Secret Key on (opt-in, Settings → Security)

1. Ask for the master password + the second factor.
2. **A recovery key is required.** If there is none, the app creates one first. Without it, losing every device and the kit would mean losing the vault forever.
3. The app generates `sk` and shows the **Emergency Kit**: printable on the web, a PDF/print on Android. It contains the Secret Key, a QR code, and a blank line for writing the master password by hand.
4. You confirm by typing the last 4 characters of the Secret Key.
5. Commit the header: `version=2`, `secretKey={id,createdAt}`, a new `passwordWrap` (KEK v2), `security.secretKey=sk`. Store `sk` on this device.
6. Other devices: after the next sync, their **open session keeps running** (the VK is unchanged). On their next unlock, the app asks for the Secret Key once (scan the QR from a device that is still unlocked, or type it from the kit).

### 4.2 New device

Install → sign in to Google → download the vault (ciphertext only). Then:

- the app sees `header.secretKey != null` and asks for the **Secret Key** (scan the QR from your other device or type it from the kit), then the **master password**, then the Authenticator code;
- `skId` checks the Secret Key before Argon2 runs.

### 4.3 Showing the QR on a device that is already unlocked

Settings → Security → "Add a device". This asks again for the master password + the second factor, then shows the QR for 60 seconds. On Android that screen uses `FLAG_SECURE`.

### 4.4 Lost the Secret Key

- **A device is still unlocked, or remembers the key:** show it again (4.3) and print a new kit.
- **No device left, but you have the recovery key:** the recovery key unwraps the VK. The app reads `security.secretKey` from inside the vault, asks you to set a new master password (the existing flow), re-wraps it with that same SK and stores it on this device. The Authenticator is still required.
- **No device, no recovery key and no kit:** the vault **cannot** be opened. This is the price of the protection, and it is why §4.1 step 2 is mandatory.

### 4.5 Rotating the Secret Key (if the kit may have been exposed)

Master password + second factor → new `sk` → re-wrap → new kit. Every other device then asks for the new Secret Key on its next unlock. The old `sk` becomes useless for the current Drive file. Old backups still open with the old `sk`, which is unavoidable.

### 4.6 Turning it off

Allowed, after master password + second factor. The vault is re-wrapped with KEK v1 and `secretKey=null`, but `version` stays 2. A warning explains that the cloud copy goes back to password protection only.

### 4.7 What does not change

- Unlock with the **recovery key**: still works on its own.
- **Biometrics / passkey** quick unlock: wraps the VK directly, so it is unaffected.
- **Trusted-device token**, TOTP and recovery codes: unchanged.
- **Changing the master password**: new Argon2 salt, same SK, new `passwordWrap`.
- **Backups (`.myvault`)**: the header inside needs password + SK, or the recovery key. The backup screen says so.

## 5. Sync: new rules

- **Never downgrade silently.** If two devices change the header at the same time and only one side has `secretKey != null` (turned on/rotated on device A, password changed on device B), **the header with the Secret Key wins**, whatever the time. A conflict notice is shown. Device B's password change has to be redone, but the Secret Key does not disappear without you knowing.
- Two different Secret Keys at the same time (rotation on two devices): the most recent one wins, as today, plus a notice. The losing device then asks for the new Secret Key.
- Rollback protection (`maxHeaderRev`) stays. A v1 header cannot overwrite a v2 header that has already been seen.

## 6. Rollout order

1. **Format support first, no UI.** The web (`src/vault/keys.ts`, `access.ts`, `types.ts`, `sync/format.ts`, `sync/engine.ts`) and Android `:core` both learn to read and write v2, with tests:
   - cross-fixtures v2 in both directions, as in stage 2
   - migration v1 → v2 → off
   - "wrong Secret Key" vs "wrong password"
   - recovery-key flow with SK
   - the no-downgrade header-merge rule
   - a v1 client refuses v2 without overwriting it
2. **Deploy the web** so every browser updates to the v2 reader.
3. **UI for turning it on, the Emergency Kit and the QR**, on the web and in the Android app (stage 3/4).
4. Turn it on for your own vault only after both apps can read v2.

This change touches the web repo `D:\DAMMYVAULT`. The Android session task so far said not to touch it, so web work needs your OK. It is better done in its own session/branch so you can review it as a separate PR.

## 7. Decisions for you

| # | Question | Recommendation |
|---|---|---|
| 1 | Opt-in, or mandatory for every vault? | **Opt-in** now. Consider making it the default for new vaults later |
| 2 | Require a recovery key before turning it on? | **Yes**, required |
| 3 | Transfer by QR between devices? | **Yes**, after master password + 2FA, with the QR shown for 60 s |
| 4 | "Don't remember on this device" option? | **Yes**, for shared PCs |
| 5 | Order: format support on web + Android first, UI after? | **Yes**, as in §6 |

## 8. Remaining limits (even with the Secret Key)

- **Not audited.**
- **A compromised device** (malware, root, an XSS on the web origin) can read the SK and the password as you type it.
- **The Emergency Kit is now a secret.** Keep it safe. Kit + password = access, just like today's password alone.
- **The TOTP stays enforced by the app.** The Secret Key is what makes protection of the cloud file *cryptographic*.
