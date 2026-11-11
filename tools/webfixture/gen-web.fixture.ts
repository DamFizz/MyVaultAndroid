/**
 * Generates core/src/test/resources/fixtures/web-vault.json using the web app's OWN modules.
 * The Kotlin tests must open this vault, verify its tokens/codes and decrypt every record.
 * Uses a test-only Argon2id cost for the vault; the default 64 MiB cost is covered by a KDF vector.
 */
import { writeFileSync } from 'node:fs';
import path from 'node:path';
import { DEFAULT_KDF, deriveBits, deriveFromPassword, importHkdfBase, setKdfCostForTesting } from '@web/lib/crypto';
import { base64ToBytes, bytesToBase64, utf8Encode } from '@web/lib/encoding';
import { base32Encode, generateTotpSecret, totp } from '@web/lib/totp';
import { createRecoveryKey, createVault, enableTotp, unlockWithPassword } from '@web/vault/access';
import { hashRecoveryCode } from '@web/vault/keys';
import { createBackup } from '@web/vault/backup';
import { emptyItem, type Collection, type VaultItem } from '@web/vault/types';
import { serializeSyncFile } from '@web/sync/format';
import { MemRepo } from './memrepo';

const OUT = path.resolve(import.meta.dirname, '../../core/src/test/resources/fixtures/web-vault.json');
const TEST_KDF = { alg: 'argon2id' as const, memoryKiB: 8192, iterations: 2, parallelism: 1 };

// "é" written decomposed (NFD): both apps must NFC-normalize before Argon2id.
const PASSWORD = 'Kata-Laluan-Café-42! 🔐';
const BACKUP_PASSWORD = 'Sandaran-Ä-2026';

test('generate web vault fixture', async () => {
  // ---------------------------------------------------------------- primitive vectors
  const kdfSalt = bytesToBase64(utf8Encode('myvault-fixture-salt-16+'));
  const argonDefault = await deriveFromPassword(PASSWORD, { ...DEFAULT_KDF, salt: kdfSalt });
  const argonTest = await deriveFromPassword(PASSWORD, { ...TEST_KDF, salt: kdfSalt });
  const ikm = base64ToBytes(bytesToBase64(utf8Encode('input key material for hkdf ✓')));
  const base = await importHkdfBase(ikm);
  const hkdfEmptySalt = await deriveBits(base, 'myvault/v1/subkey/records', '', 256);
  const hkdfSalted = await deriveBits(base, 'myvault/v1/kek/password', 'vault-id-ÿ', 512);
  const rfcSecret = base32Encode(utf8Encode('12345678901234567890'));
  const totpVectors = await Promise.all(
    [59, 1111111109, 1111111111, 1234567890, 2000000000, 20000000000].map(async (t) => ({
      timeSec: t,
      code6: await totp(rfcSecret, t * 1000, 6),
      code8: await totp(rfcSecret, t * 1000, 8),
    })),
  );

  // ---------------------------------------------------------------- the vault
  setKdfCostForTesting(TEST_KDF);
  const repo = new MemRepo();
  const session = await createVault(repo.asRepo(), PASSWORD);
  const now = Date.now();

  const section: Collection = {
    id: 'section-root', kind: 'section', name: 'Peribadi', icon: 'folder', color: '#22c55e',
    parentId: null, pinned: true, order: 0, createdAt: now, updatedAt: now,
  };
  const child: Collection = { ...section, id: 'section-child', name: 'Bank & Kewangan', parentId: 'section-root', pinned: false, order: 1 };
  await session.saveCollections([section, child]);
  const financeId = session.collections().find((c) => c.name === 'Finance')!.id;

  const login: VaultItem & { futureField?: unknown } = {
    ...emptyItem('login', 'item-login', now),
    title: 'Maybank2u — akaun utama 🏦',
    url: 'https://www.maybank2u.com.my',
    username: 'dam.fizz',
    email: 'someone@example.com',
    password: 'S3cr3t!"\\/\n\t<>&\'ünï',
    notes: 'Multi-line\nnota "quoted"',
    categoryId: financeId,
    sectionId: 'section-child',
    tags: ['bank', 'penting'],
    customFields: [
      { id: 'cf1', label: 'PIN', value: '0042', hidden: true },
      { id: 'cf2', label: 'Soalan keselamatan', value: 'Kucing pertama?', hidden: false },
    ],
    favorite: true,
    reprompt: true,
    passwordChangedAt: now - 1000,
    passwordHistory: [{ password: 'old-pass-1', changedAt: now - 86_400_000 }],
    // A field from a hypothetical newer web version: Android must keep it when editing.
    futureField: { nested: [1, 2, 3], flag: true },
  };
  const note: VaultItem = { ...emptyItem('note', 'item-note', now), title: 'Nota rahsia', notes: 'ini akan dipadam pada Android' };
  const wifi: VaultItem = { ...emptyItem('wifi', 'item-wifi', now), title: 'Rumah', ssid: 'DamFi', password: 'wifi-pass', wifiSecurity: 'WPA3' };
  const lic: VaultItem = { ...emptyItem('license', 'item-license', now), title: 'Adobe', product: 'After Effects', licenseKey: 'AAAA-BBBB', archivedAt: now };
  const trashed: VaultItem = { ...emptyItem('login', 'item-gone', now), title: 'Dibuang' };
  await session.saveItems([login, note, wifi, lic, trashed]);
  await session.purgeItems(['item-gone']);
  await session.saveSettings({ passwordHistoryLimit: 10, defaultCategoryId: financeId });
  await session.saveItem({ ...wifi, notes: 'edited twice → rev 2' });

  const recoveryKey = await createRecoveryKey(session, PASSWORD);
  const totpSecret = generateTotpSecret();
  const recoveryCodes = await enableTotp(session, totpSecret, await totp(totpSecret));
  const backup = await createBackup(session, BACKUP_PASSWORD, 'web-fixture');

  // Trust this device for 30 days via a real unlock (clear the replay step left by enrollment).
  await repo.deleteLocal(`totpLastStep:${session.vaultId}`);
  const step = await unlockWithPassword(repo.asRepo(), PASSWORD);
  expect(step.type).toBe('second-factor');
  if (step.type !== 'second-factor') throw new Error('unreachable');
  const s2 = await step.challenge.verifyTotp(await totp(totpSecret), 30);
  const generatedAt = Date.now();

  const header = (await repo.getHeader())!;
  const records = await repo.allRecords();
  const expected: Record<string, unknown> = {};
  for (const d of s2.allDecrypted()) expected[d.record.id] = d.payload;
  expect(Object.keys(expected).length).toBe(records.length);

  const fixture = {
    comment: 'Generated by tools/webfixture/gen-web.fixture.ts from the web app modules. Do not edit.',
    generatedAt,
    password: PASSWORD,
    backupPassword: BACKUP_PASSWORD,
    totpSecret,
    recoveryKey,
    recoveryCodes,
    recoveryCodeHashSample: { vaultId: header.vaultId, code: 'abcde-fghij', hash: await hashRecoveryCode(header.vaultId, 'abcde-fghij') },
    device: await repo.getLocal('device'),
    trustToken: await repo.getLocal(`totpTrust:${header.vaultId}`),
    syncFile: serializeSyncFile(header, records, 'web-fixture-writer'),
    backup,
    expected,
    vectors: {
      kdfSalt,
      argon2Default: { params: DEFAULT_KDF, out: bytesToBase64(argonDefault) },
      argon2Test: { params: TEST_KDF, out: bytesToBase64(argonTest) },
      hkdf: {
        ikm: bytesToBase64(ikm),
        emptySalt: { info: 'myvault/v1/subkey/records', salt: '', out: bytesToBase64(hkdfEmptySalt) },
        salted: { info: 'myvault/v1/kek/password', salt: 'vault-id-ÿ', bits: 512, out: bytesToBase64(hkdfSalted) },
      },
      totp: { secret: rfcSecret, cases: totpVectors },
    },
  };
  writeFileSync(OUT, JSON.stringify(fixture, null, 2) + '\n');
  setKdfCostForTesting(null);
});
