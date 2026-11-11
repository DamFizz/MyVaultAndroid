/**
 * Verifies tools/webfixture/out/kotlin-vault.json (written by KotlinToWebTest) with the web app's
 * OWN modules: vaults created or edited by the Android core must unlock and decrypt on the web.
 * Run `./gradlew :core:test` first.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { totp } from '@web/lib/totp';
import { unlockWithPassword, unlockWithRecoveryKey, WrongPasswordError, InvalidCodeError } from '@web/vault/access';
import { openBackup } from '@web/vault/backup';
import { parseSyncFile } from '@web/sync/format';
import type { VaultSession } from '@web/vault/session';
import { MemRepo } from './memrepo';

const fx = JSON.parse(readFileSync(path.resolve(import.meta.dirname, 'out/kotlin-vault.json'), 'utf8'));

async function repoFor(v: any, opts: { device?: boolean; trust?: boolean } = {}) {
  const repo = new MemRepo();
  const file = parseSyncFile(v.syncFile);
  await repo.replaceVault(file.header, file.records);
  if (opts.device && v.device) await repo.setLocal('device', v.device);
  if (opts.trust && v.trustToken) await repo.setLocal(`totpTrust:${file.header.vaultId}`, v.trustToken);
  return { repo, file };
}

async function unlockWithTotp(repo: MemRepo, password: string, secret: string): Promise<VaultSession> {
  const step = await unlockWithPassword(repo.asRepo(), password);
  expect(step.type).toBe('second-factor');
  if (step.type !== 'second-factor') throw new Error('unreachable');
  return step.challenge.verifyTotp(await totp(secret));
}

function expectPayloadsMatch(session: VaultSession, expected: Record<string, unknown>) {
  expect(session.corruptedIds).toEqual([]);
  const actual: Record<string, unknown> = {};
  for (const d of session.allDecrypted()) actual[d.record.id] = d.payload;
  expect(Object.keys(actual).sort()).toEqual(Object.keys(expected).sort());
  for (const id of Object.keys(expected)) expect(actual[id], id).toEqual(expected[id]);
}

describe('vault created by the Android core', () => {
  const v = fx.created;

  test('unlocks with password + TOTP and decrypts every record', async () => {
    const { repo } = await repoFor(v);
    await expect(unlockWithPassword(repo.asRepo(), 'wrong')).rejects.toBeInstanceOf(WrongPasswordError);
    const session = await unlockWithTotp(repo, v.password, v.totpSecret);
    expectPayloadsMatch(session, v.expected);
    const login = session.getItem(v.loginId)!;
    expect(login.title).toBe('GitHub — kerja 💼');
    expect(login.categoryId).toBe(session.collections().find((c) => c.name === 'Work')!.id);
    expect(session.getItem(v.goneId)).toBeUndefined();
    expect(session.settings().trashRetentionDays).toBe(7);
    expect(session.collections().length).toBe(9);
    expect(session.security.totp?.secret).toBe(v.totpSecret);
    expect(session.security.recoveryCodeHashes.length).toBe(10);
  });

  test('Kotlin trust token skips the code on the same device only', async () => {
    const trusted = await repoFor(v, { device: true, trust: true });
    expect((await unlockWithPassword(trusted.repo.asRepo(), v.password)).type).toBe('unlocked');
    const other = await repoFor(v, { trust: true }); // no device record → a new random device id
    expect((await unlockWithPassword(other.repo.asRepo(), v.password)).type).toBe('second-factor');
  });

  test('Kotlin recovery codes work once', async () => {
    const { repo } = await repoFor(v);
    const step = await unlockWithPassword(repo.asRepo(), v.password);
    if (step.type !== 'second-factor') throw new Error('expected second factor');
    const session = await step.challenge.useRecoveryCode(v.recoveryCodes[0]);
    expect(session.security.recoveryCodeHashes.length).toBe(9);
    const again = await unlockWithPassword(repo.asRepo(), v.password);
    if (again.type !== 'second-factor') throw new Error('expected second factor');
    await expect(again.challenge.useRecoveryCode(v.recoveryCodes[0])).rejects.toBeInstanceOf(InvalidCodeError);
  });

  test('Kotlin recovery key resets the master password', async () => {
    const { repo } = await repoFor(v);
    const step = await unlockWithRecoveryKey(repo.asRepo(), v.recoveryKey, 'Web-Selepas-Pemulihan-2');
    if (step.type !== 'second-factor') throw new Error('expected second factor');
    const session = await step.challenge.verifyTotp(await totp(v.totpSecret));
    expect(session.items().length).toBe(2);
    await expect(unlockWithPassword(repo.asRepo(), v.password)).rejects.toBeInstanceOf(WrongPasswordError);
  });

  test('Kotlin backup opens on the web', async () => {
    const opened = await openBackup(v.backup, v.backupPassword);
    expect(opened.header.vaultId).toBe(parseSyncFile(v.syncFile).header.vaultId);
    expect(opened.appVersion).toBe('android-fixture');
  });
});

describe('web vault after edits made by the Android core', () => {
  const v = fx.editedWeb;

  test('opens with the new master password and shows the Android edits', async () => {
    const { repo } = await repoFor(v);
    await expect(unlockWithPassword(repo.asRepo(), v.oldPassword)).rejects.toBeInstanceOf(WrongPasswordError);
    const session = await unlockWithTotp(repo, v.password, v.totpSecret);
    expectPayloadsMatch(session, v.expected);
    const login = session.getItem('item-login')! as any;
    expect(login.title).toBe('Maybank2u (disunting di Android)');
    expect(login.futureField).toEqual({ nested: [1, 2, 3], flag: true }); // unknown field survived
    expect(login.sectionId).toBe('section-child');
    expect(login.passwordHistory.length).toBe(2);
    expect(session.getItem('item-note')).toBeUndefined();
    expect(session.getItem('item-android-wifi')?.ssid).toBe('Office-5G');
    expect(session.collections().some((c) => c.id === 'col-android')).toBe(true);
  });

  test('new recovery codes work and old ones do not', async () => {
    const { repo } = await repoFor(v);
    const step = await unlockWithPassword(repo.asRepo(), v.password);
    if (step.type !== 'second-factor') throw new Error('expected second factor');
    await expect(step.challenge.useRecoveryCode(v.oldRecoveryCode)).rejects.toBeInstanceOf(InvalidCodeError);
    const session = await step.challenge.useRecoveryCode(v.recoveryCodes[5]);
    expect(session.items().length).toBe(4);
  });

  test('recovery key still unwraps the vault key', async () => {
    const { repo } = await repoFor(v);
    const step = await unlockWithRecoveryKey(repo.asRepo(), v.recoveryKey, 'Web-Lagi-3');
    expect(step.type).toBe('second-factor');
  });
});
