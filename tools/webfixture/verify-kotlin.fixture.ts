/**
 * Verifies tools/webfixture/out/kotlin-vault.json (written by KotlinToWebTest) with the web app's
 * OWN modules: vaults created or edited by the Android core must unlock and decrypt on the web.
 * Run `./gradlew :core:test` first.
 */
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { totp } from '@web/lib/totp';
import { disableSecretKey, unlockWithPassword, unlockWithRecoveryKey, WrongPasswordError, InvalidCodeError } from '@web/vault/access';
import { SecretKeyRequiredError, WrongSecretKeyError } from '@web/vault/secret-key';
import { generateSecretKey } from '@web/vault/keys';
import { openBackup } from '@web/vault/backup';
import { parseSyncFile } from '@web/sync/format';
import type { VaultSession } from '@web/vault/session';
import { MemRepo, MemRemote } from './memrepo';
import { SyncEngine } from '@web/sync/engine';
import { downloadVaultToDevice } from '@web/sync/bootstrap';
import { emptyItem } from '@web/vault/types';
import type { RemoteStore } from '@web/sync/remote';

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

describe('Secret Key (format v2) vault created by the Android core', () => {
  const v = fx.createdV2;

  test('needs the Secret Key on a new device, then decrypts every record', async () => {
    const { repo, file } = await repoFor(v);
    expect(file.header.version).toBe(2);
    await expect(unlockWithPassword(repo.asRepo(), v.password)).rejects.toBeInstanceOf(SecretKeyRequiredError);
    await expect(unlockWithPassword(repo.asRepo(), v.password, { secretKey: (await generateSecretKey()).display })).rejects.toBeInstanceOf(WrongSecretKeyError);
    await expect(unlockWithPassword(repo.asRepo(), 'wrong', { secretKey: v.secretKey })).rejects.toBeInstanceOf(WrongPasswordError);
    const step = await unlockWithPassword(repo.asRepo(), v.password, { secretKey: v.secretKey.toLowerCase() });
    if (step.type !== 'unlocked') throw new Error('expected unlocked');
    expectPayloadsMatch(step.session, v.expected);
    step.session.lock();
    expect((await unlockWithPassword(repo.asRepo(), v.password)).type).toBe('unlocked'); // remembered on this device
  });

  test('Kotlin Secret Key vault: recovery key on the web recovers the Secret Key', async () => {
    const { repo } = await repoFor(v);
    const step = await unlockWithRecoveryKey(repo.asRepo(), v.recoveryKey, 'Web-Pulih-SK-4');
    if (step.type !== 'unlocked') throw new Error('expected unlocked');
    step.session.lock();
    expect((await unlockWithPassword(repo.asRepo(), 'Web-Pulih-SK-4')).type).toBe('unlocked');
  });

  test('the web can turn the Kotlin Secret Key off again', async () => {
    const { repo } = await repoFor(v);
    const step = await unlockWithPassword(repo.asRepo(), v.password, { secretKey: v.secretKey });
    if (step.type !== 'unlocked') throw new Error('expected unlocked');
    await disableSecretKey(step.session, v.password);
    const again = new MemRepo();
    await again.replaceVault((await repo.getHeader())!, await repo.allRecords());
    expect((await unlockWithPassword(again.asRepo(), v.password)).type).toBe('unlocked');
  });
});

describe('sync round trip: web device ↔ Android sync engine on the same cloud file', () => {
  const v = fx.syncRoundTrip;

  test('the web engine pulls what the Android engine pushed, with no conflicts, and can push back', async () => {
    // A web device that already had the vault (its sync base is the original file).
    const remote = new MemRemote();
    await remote.create(v.originalSyncFile);
    const repo = new MemRepo();
    await downloadVaultToDevice(repo.asRepo(), remote as unknown as RemoteStore);
    const session = await unlockWithTotp(repo, v.password, v.totpSecret);
    expect(session.getItem('from-android-sync')).toBeUndefined();

    // The Android engine has written the cloud file in the meantime.
    remote.content = v.remoteAfterKotlin;
    remote.version++;
    const report = await new SyncEngine(session, remote as unknown as RemoteStore).sync();
    expect(report.conflicts).toEqual([]);
    expect(report.skippedCorrupt).toBe(0);
    expect(report.pulled).toBeGreaterThanOrEqual(3);
    expect(session.corruptedIds).toEqual([]);
    expect(session.getItem('from-android-sync')?.title).toBe('Ditambah oleh sync Android');
    expect(session.getItem('item-wifi')?.notes).toBe('disunting oleh enjin sync Android');
    expect(session.getItem('item-license')).toBeUndefined();
    expect((session.getItem('item-login') as any).futureField).toEqual({ nested: [1, 2, 3], flag: true });

    // Nothing left to do; then a web edit goes back up and the file stays valid.
    const again = await new SyncEngine(session, remote as unknown as RemoteStore).sync();
    expect(again.pulled + again.pushed).toBe(0);
    await session.saveItem({ ...emptyItem('note', 'web-after-android', Date.now()), title: 'Web selepas Android' });
    const push = await new SyncEngine(session, remote as unknown as RemoteStore).sync();
    expect(push.pushed).toBe(1);
    expect(parseSyncFile(remote.content!).records.some((r) => r.id === 'web-after-android')).toBe(true);
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
