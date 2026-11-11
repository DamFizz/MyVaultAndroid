/** In-memory stand-in for the web's Dexie VaultRepository (same method names, structured copies). */
import type { VaultRepository } from '@web/vault/db';
import type { EncryptedRecord, VaultHeader } from '@web/vault/types';

export class MemRepo {
  header: VaultHeader | undefined;
  records = new Map<string, EncryptedRecord>();
  local = new Map<string, unknown>();

  async getHeader() { return this.header ? structuredClone(this.header) : undefined; }
  async putHeader(h: VaultHeader) { this.header = structuredClone(h); }
  async allRecords() { return [...this.records.values()].map((r) => structuredClone(r)); }
  async getRecord(id: string) { const r = this.records.get(id); return r ? structuredClone(r) : undefined; }
  async putRecords(rs: EncryptedRecord[]) { for (const r of rs) this.records.set(r.id, structuredClone(r)); }
  async deleteRecords(ids: string[]) { for (const id of ids) this.records.delete(id); }
  async getLocal<T>(k: string) { const v = this.local.get(k); return (v === undefined ? undefined : structuredClone(v)) as T | undefined; }
  async setLocal<T>(k: string, v: T) { this.local.set(k, structuredClone(v)); }
  async deleteLocal(k: string) { this.local.delete(k); }
  async replaceVault(h: VaultHeader, rs: EncryptedRecord[], clear: string[] = []) {
    this.records.clear();
    for (const r of rs) this.records.set(r.id, structuredClone(r));
    this.header = structuredClone(h);
    for (const k of clear) this.local.delete(k);
  }
  async createSnapshot() { return null; }

  asRepo(): VaultRepository { return this as unknown as VaultRepository; }
}
