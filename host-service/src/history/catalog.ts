import type { Codex } from '../providers/codex.js';
import type { RpcMessage } from '../providers/codex.js';
import type { Store } from '../storage/store.js';

const sources = ['cli', 'vscode', 'exec', 'appServer', 'subAgent', 'subAgentReview', 'subAgentCompact', 'subAgentThreadSpawn', 'subAgentOther', 'unknown'];
type Summary = { id: string; name: string | null; preview: string; cwd: string; updatedAt: number; model: string | null; reasoningEffort: string | null };
function summary(thread: any): Summary {
  return { id: thread.id, name: thread.name ?? null, preview: (thread.preview ?? '').slice(0, 200), cwd: thread.cwd,
    updatedAt: thread.updatedAt ?? 0, model: thread.model ?? null, reasoningEffort: thread.reasoningEffort ?? null };
}

/** A persistent metadata index, maintained independently of phone connections. */
export class SessionCatalog {
  private timer?: NodeJS.Timeout;
  private running?: Promise<void>;
  private stopped = false;
  private lastFull = 0;
  private watermark = 0;
  private error: string | null = null;
  private notification = (message: RpcMessage) => {
    if (['thread/started', 'thread/name/updated', 'thread/archived', 'thread/unarchived', 'turn/completed'].includes(message.method ?? '')) {
      this.lastFull = 0;
      this.schedule(100);
    }
  };

  constructor(private codex: Codex, private store: Store) {
    store.db.exec(`CREATE TABLE IF NOT EXISTS session_catalog (id TEXT PRIMARY KEY, updatedAt REAL NOT NULL, summary TEXT NOT NULL);
      CREATE INDEX IF NOT EXISTS session_catalog_order ON session_catalog(updatedAt DESC, id DESC);
      CREATE INDEX IF NOT EXISTS events_type_sequence ON events(type, seq);
      CREATE TABLE IF NOT EXISTS session_catalog_state (id INTEGER PRIMARY KEY CHECK(id=1), syncedAt TEXT NOT NULL);`);
  }
  status() {
    const state = this.store.db.prepare('SELECT syncedAt FROM session_catalog_state WHERE id=1').get();
    return { ready: !!state, syncedAt: state?.syncedAt ?? null, error: this.error,
      total: Number(this.store.db.prepare('SELECT COUNT(*) AS count FROM session_catalog').get()!.count) };
  }
  start() {
    this.stopped = false;
    this.codex.on('notification', this.notification);
    this.schedule(0);
  }
  close() {
    this.stopped = true; clearTimeout(this.timer);
    this.codex.off('notification', this.notification);
  }
  private schedule(delay: number) {
    if (this.stopped) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => {
      void this.refresh().catch(e => { this.error = (e as Error).message; })
        .finally(() => this.schedule(2000));
    }, delay);
    this.timer.unref();
  }
  refresh(full = Date.now() - this.lastFull >= 60_000): Promise<void> {
    if (this.running) return this.running;
    this.running = this.scan(full).finally(() => { this.running = undefined; });
    return this.running;
  }
  private async scan(full: boolean) {
    await this.codex.start();
    const incoming = new Map<string, Summary>();
    let cursor: string | null = null;
    do {
      const page = await this.codex.request('thread/list', { limit: 100, cursor, sortKey: 'updated_at', sortDirection: 'desc',
        modelProviders: [], sourceKinds: sources, useStateDbOnly: true });
      if (this.stopped) return;
      for (const thread of page.data) incoming.set(thread.id, summary(thread));
      cursor = page.nextCursor ?? null;
      // Re-read the timestamp boundary because Codex timestamps have second precision.
      if (!full && page.data.some((thread: any) => thread.updatedAt < this.watermark - 5)) break;
    } while (cursor);
    this.apply([...incoming.values()], full);
    this.watermark = Math.max(this.watermark, ...[...incoming.values()].map(t => t.updatedAt));
    if (full) this.lastFull = Date.now();
    this.error = null;
  }
  private apply(incoming: Summary[], full: boolean) {
    const old = new Map(this.store.db.prepare('SELECT id, summary FROM session_catalog').all().map(row => [String(row.id), String(row.summary)]));
    const seen = new Set(incoming.map(t => t.id));
    const changed = incoming.filter(t => old.get(t.id) !== JSON.stringify(t));
    const removed = full ? [...old.keys()].filter(id => !seen.has(id)) : [];
    if (!full && !changed.length && !removed.length) return;
    this.store.transaction(() => {
      const upsert = this.store.db.prepare('INSERT INTO session_catalog VALUES (?,?,?) ON CONFLICT(id) DO UPDATE SET updatedAt=excluded.updatedAt, summary=excluded.summary');
      for (const thread of changed) upsert.run(thread.id, thread.updatedAt, JSON.stringify(thread));
      for (const id of removed) this.store.db.prepare('DELETE FROM session_catalog WHERE id=?').run(id);
      this.store.db.prepare('INSERT INTO session_catalog_state VALUES (1,?) ON CONFLICT(id) DO UPDATE SET syncedAt=excluded.syncedAt').run(new Date().toISOString());
      for (let i = 0; i < Math.max(changed.length, removed.length); i += 40) {
        this.store.insertEvent('sessions.changed', { upserted: changed.slice(i, i + 40).map(t => this.decorate(t)), removed: removed.slice(i, i + 40) });
      }
    });
    if (changed.length || removed.length) this.store.emit('event');
  }
  private decorate(thread: Summary) { return { ...thread, managed: this.store.managed(thread.id) }; }
  async list(cursor: string | null, search = '', after?: number) {
    if (!this.status().ready) await this.refresh();
    const position = cursor ? JSON.parse(Buffer.from(cursor, 'base64url').toString()) : null;
    if (position && (!Number.isFinite(position.updatedAt) || typeof position.id !== 'string')) throw new Error('会话游标无效，请刷新列表');
    const rows = this.store.db.prepare(`SELECT summary FROM session_catalog
      WHERE (? = '' OR instr(lower(COALESCE(json_extract(summary,'$.name'), '') || ' ' || json_extract(summary,'$.preview')), lower(?)) > 0)
      AND (? IS NULL OR updatedAt < ? OR (updatedAt = ? AND id < ?))
      ORDER BY updatedAt DESC, id DESC LIMIT 41`).all(search, search, position?.updatedAt ?? null, position?.updatedAt ?? null, position?.updatedAt ?? null, position?.id ?? null);
    const data = rows.slice(0, 40).map(row => this.decorate(JSON.parse(String(row.summary))));
    const last = data.at(-1);
    const catalogCursor = this.store.cursor();
    const upserted = new Map<string, any>();
    const removed = new Set<string>();
    const reset = after != null && after > catalogCursor;
    if (after != null && !reset) {
      for (const row of this.store.db.prepare("SELECT data FROM events WHERE type='sessions.changed' AND seq>? AND seq<=? ORDER BY seq").all(after, catalogCursor)) {
        const change = JSON.parse(String(row.data));
        for (const thread of change.upserted) { upserted.set(thread.id, this.decorate(thread)); removed.delete(thread.id); }
        for (const id of change.removed) { upserted.delete(id); removed.add(id); }
      }
    }
    return { data, nextCursor: rows.length > 40 && last ? Buffer.from(JSON.stringify({ updatedAt: last.updatedAt, id: last.id })).toString('base64url') : null,
      catalog: this.status(), catalogCursor, reset, changes: { upserted: [...upserted.values()], removed: [...removed] } };
  }
}
