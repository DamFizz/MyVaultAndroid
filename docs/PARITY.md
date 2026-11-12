# Web parity checklist

Goal set by the owner: **every feature of the MyVault web app exists in the Android app**, with an iOS-style Liquid Glass UI. The app is not "done" while any row below is ⬜.

Legend: ✅ in the APK · 🟡 logic in `:core`, no screen yet · ⬜ not started. Web source in brackets.

## 1. Onboarding and lock screen [`screens/Onboarding`, `LockScreen`]

| Feature | Status |
|---|---|
| Create vault | ✅ (basic; needs the 3-step intro, strength meter, tips, acknowledgement) |
| "I already have a vault": download from Google Drive | ⬜ |
| "I already have a vault": restore a backup file | ✅ |
| Unlock: master password, TOTP, recovery code, trust device 30 days | ✅ |
| Unlock: Secret Key (format v2) | ✅ |
| Quick unlock with the device (biometrics) | ✅ (not yet tested on a real fingerprint) |
| Forgot password: recovery key → new master password | ✅ |
| Forgot password: erase vault and start over | ✅ (in Settings; add to lock screen) |
| Lock reasons shown (idle, background, restored, downloaded) | ⬜ |
| Throttle message with countdown | ✅ |

## 2. Vault and items [`views/ItemsView`, `items/*`, `filtering.ts`]

| Feature | Status |
|---|---|
| Overview/dashboard: recommendations, favourites, recently used, conflict and corruption notices | ⬜ |
| List with search (never searches passwords) | ✅ |
| Views: all, favourites, recently added, recently used, by type, category, folder (nested), tag, uncategorized, archive, trash | ✅ |
| Sort: title, updated, created, last used | ✅ |
| Item detail: all fields, reveal, copy, open website (http/https only) | ✅ |
| Detail: password history, created/modified/password-changed dates | ✅ (weak-password warning and last-used date ⬜) |
| Reprompt for protected items | ✅ |
| Create and edit all 6 item types, custom fields, tags, validation, discard-changes prompt | ✅ (tag suggestions ⬜) |
| Favourite, archive, trash, restore, delete forever, duplicate, undo toast | ✅ (move dialog ⬜; category/folder are set in the editor) |
| Multi-select with bulk move/archive/trash/tag | ⬜ |
| Trash retention (auto-purge on unlock), empty trash | ✅ |
| Reveal timeout (re-hide secrets after N seconds) | ⬜ |

## 3. Organize [`views/OrganizeView`]

| Feature | Status |
|---|---|
| Categories and folders: create, rename, icon, colour, pin, reorder, nest folders | ✅ |
| Delete with "move items to…" (items are never deleted) | ✅ |

## 4. Generator [`views/GeneratorView`, `lib/generator.ts`, `lib/strength.ts`]

| Feature | Status |
|---|---|
| Password: length, character classes, exclude ambiguous, avoid repeats | ✅ |
| Passphrase: BIP-39 words, separator, capitalize, number | ✅ |
| Strength meter with entropy bits | ✅ (same scores as the web for 36 samples; warning texts ⬜) |
| Save to a new login or replace an existing login's password (history kept) | ✅ |

## 5. Security center [`views/SecurityView`, `lib/security-analysis.ts`, `lib/hibp.ts`]

| Feature | Status |
|---|---|
| Score and summary; weak, reused, duplicate, old passwords; missing URL/login; uncategorized | ✅ (report identical to the web's on a fixture) |
| Breach check (Have I Been Pwned k-anonymity, opt-in) | ✅ (logic tested offline; live request not exercised in testing) |

## 6. Sync [`screens/SyncView`, `sync/*`, `state/sync-controller.ts`]

| Feature | Status |
|---|---|
| Three-way merge | 🟡 |
| Sync engine: conflict copies, edit beats delete, rollback protection, retry on concurrent write, Secret Key no-downgrade | ⬜ |
| Google sign-in, Drive appDataFolder client | ⬜ (needs the owner's Android OAuth client ID) |
| Connect, sync now, auto-sync, switch account, disconnect, delete cloud copy | ⬜ |
| Status pill, errors, vault-mismatch resolution (keep local / use cloud), conflict review | ⬜ |

## 7. Backup and restore [`views/BackupView`, `vault/backup.ts`, `lib/csv.ts`]

| Feature | Status |
|---|---|
| Open a backup (verify, show info) | ✅ |
| Restore: replace | ✅ |
| Restore: merge ("restored copy", nothing overwritten) | ✅ |
| Export encrypted backup (master or separate password) | ✅ |
| Safety snapshots before destructive operations; list, restore, delete | ✅ |
| CSV import (Bitwarden, Chrome/Edge, Firefox, 1Password, LastPass, KeePass, Proton Pass, generic) | ✅ |
| Last-backup date | ✅ (reminder ⬜) |

## 8. Settings [`views/SettingsView`, `state/prefs*.ts`, `state/autolock.ts`]

| Feature | Status |
|---|---|
| Appearance: theme (dark/light/system), accent colour, language (Android 13+) | ✅ (motion and density options ⬜) |
| Change master password (with strength check) | ✅ |
| Authenticator: enable with QR, disable; recovery codes regenerate and copy | ✅ (print ⬜; not yet exercised end to end on the emulator) |
| Recovery key: create, replace, remove, shown once with copy | ✅ (printable kit ⬜) |
| Secret Key: enable, rotate, disable, Emergency Kit, "add a device" QR | 🟡 (web UI also pending) |
| Quick unlock on/off | ✅ |
| Trusted device status and forget | ✅ |
| Auto-lock timer, lock when hidden, reveal timeout, clipboard clear time | ✅ |
| Vault settings: default category, password history limit, old-password days, trash retention, reprompt by default | ✅ |
| Device name, record count, about/security/privacy text | ✅ (recovery guide ⬜) |

## 9. Liquid Glass UI (iOS-style)

| Piece | Status |
|---|---|
| Real backdrop blur behind every glass surface (API 31+ `RenderEffect`) | ✅ |
| Refraction + specular edge + chromatic fringe (API 33+ AGSL `RuntimeShader`), light follows device tilt | ✅ |
| Fallback for API 26–30 (opaque tinted panel, no blur) | ✅ (untested on an old device) |
| Floating glass tab bar and top buttons that content scrolls under | ✅ |
| Flowing selection pill, press swell, spring sheets (drag to dismiss), alerts, toasts | ✅ (popup menus growing from the touch point ⬜) |
| Light and dark glass (follows the system) | ✅ (accent choice and reduced motion ⬜, with Settings) |
| Jank measured (Macrobenchmark/JankStats) on the owner's phone | ⬜ |

## Build order

1. **Liquid Glass foundation**: backdrop-blur + AGSL shader layer, glass tab bar/top bar/sheets, motion system, light/dark/accent. Every later screen is built on it.
2. **Editing**: item editor for all types, favourite/archive/trash/restore/purge/duplicate/move, multi-select, all list views and sorting, organize screen.
3. **Generator and Security center** (ports of `generator.ts`, `strength.ts`, `security-analysis.ts`, `hibp.ts`, with tests against the web's outputs).
4. **Settings and security management**: change password, Authenticator enrolment with QR, recovery codes/key/kit, Secret Key kit and QR, all preferences, snapshots, export, merge-restore, CSV import.
5. **Sync**: port the engine with cross-tests against the web engine, Google sign-in, Drive client, sync screen. Needs the Android OAuth client ID.
6. **Dashboard, polish, performance pass on the real phone**, then Autofill (Android-only extra).
