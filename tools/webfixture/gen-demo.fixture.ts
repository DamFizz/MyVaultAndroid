/**
 * Writes tools/webfixture/out/demo.myvault + demo.json: a small backup made by the web app's own code,
 * used to smoke-test the Android app's import → unlock → two-step flow on an emulator.
 * ASCII-only passwords so they can be typed with `adb shell input text`.
 */
import { mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { setKdfCostForTesting } from '@web/lib/crypto';
import { generateTotpSecret, totp } from '@web/lib/totp';
import { createRecoveryKey, createVault, enableTotp } from '@web/vault/access';
import { createBackup } from '@web/vault/backup';
import { emptyItem } from '@web/vault/types';
import { MemRepo } from './memrepo';

test('generate demo backup', async () => {
  setKdfCostForTesting(null); // real 64 MiB Argon2id, to measure it on the device
  const repo = new MemRepo();
  const s = await createVault(repo.asRepo(), 'DemoMaster2026');
  const now = Date.now();
  const finance = s.collections().find((c) => c.name === 'Finance')!.id;
  await s.saveItems([
    { ...emptyItem('login', 'demo-1', now), title: 'Maybank2u', username: 'dam.demo', password: 'Demo-Pass-123', url: 'https://maybank2u.com.my', categoryId: finance, favorite: true },
    { ...emptyItem('login', 'demo-2', now), title: 'GitHub', username: 'DamFizz', password: 'gh-demo-secret', reprompt: true, url: 'https://github.com' },
    { ...emptyItem('wifi', 'demo-3', now), title: 'Rumah', ssid: 'DamFi-5G', password: 'wifi-demo', wifiSecurity: 'WPA3' },
    { ...emptyItem('note', 'demo-4', now), title: 'Nota demo', notes: 'Baris satu\nBaris dua' },
  ]);
  const recoveryKey = await createRecoveryKey(s, 'DemoMaster2026');
  const secret = generateTotpSecret();
  await enableTotp(s, secret, await totp(secret));
  const out = path.resolve(import.meta.dirname, 'out');
  mkdirSync(out, { recursive: true });
  writeFileSync(path.join(out, 'demo.myvault'), await createBackup(s, 'DemoBackup2026', 'web-demo'));
  writeFileSync(path.join(out, 'demo.json'), JSON.stringify({ master: 'DemoMaster2026', backup: 'DemoBackup2026', totpSecret: secret, recoveryKey }, null, 2));
}, 120_000);
