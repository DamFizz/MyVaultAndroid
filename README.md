# MyVault Android

Native Android version of MyVault (Kotlin and Jetpack Compose, in progress). It uses exactly the same vault format as the web app (`D:\DAMMYVAULT`), so one vault, synced through Google Drive, opens on both.

- `core/` — pure Kotlin: Argon2id, HKDF, AES-GCM, vault keys, TOTP, unlock flow, three-way merge, backups.
- `tools/webfixture/` — compatibility tests that use the web app's own code (via its vitest).
- `docs/PLAN.md` — status, compatibility proof, plan for stages 3–5, and security limits.

```bash
bash tools/compat-check.sh
```

Requires the Android Studio JBR (JDK 21) and `D:\DAMMYVAULT` with `node_modules` installed. Not audited; see the limits in `docs/PLAN.md`.
