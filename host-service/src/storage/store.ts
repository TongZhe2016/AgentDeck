import { DatabaseSync } from 'node:sqlite';
import { EventEmitter } from 'node:events';
import { randomUUID } from 'node:crypto';
import type { ExecutionSettings } from '../providers/execution-settings.js';

export type Run = { id: string; requestId: string; threadId: string; turnId: string | null; state: string; text: string; error: string | null; createdAt: string };
export type Approval = { id: string; runId: string; method: string; params: any; state: string };
export type StoredEvent = { seq: number; type: string; data: any; createdAt: string };
export class Store extends EventEmitter {
  readonly db: DatabaseSync;
  constructor(path: string) {
    super();
    this.db = new DatabaseSync(path);
    this.db.exec(`PRAGMA journal_mode=WAL; PRAGMA synchronous=FULL;
      CREATE TABLE IF NOT EXISTS runs (id TEXT PRIMARY KEY, requestId TEXT UNIQUE NOT NULL, threadId TEXT NOT NULL,
        turnId TEXT, state TEXT NOT NULL, text TEXT NOT NULL, error TEXT, createdAt TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS events (seq INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT NOT NULL, data TEXT NOT NULL, createdAt TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS approvals (id TEXT PRIMARY KEY, runId TEXT NOT NULL, method TEXT NOT NULL, params TEXT NOT NULL, state TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS managed (threadId TEXT PRIMARY KEY);
      CREATE TABLE IF NOT EXISTS session_settings (threadId TEXT PRIMARY KEY, settings TEXT NOT NULL);
    `);
    const interrupted = this.db.prepare("SELECT * FROM runs WHERE state IN ('queued','running','waiting_approval','waiting_input')").all() as Run[];
    for (const run of interrupted) this.updateRun(run.id, { state: 'unknown', error: '电脑服务重启，执行结果待核实；不会自动重发。' });
    const pending = this.approvals();
    this.db.exec("UPDATE approvals SET state='expired' WHERE state='pending'");
    for (const approval of pending) this.event('approval.resolved', { id: approval.id, runId: approval.runId });
  }
  transaction<T>(action: () => T): T {
    this.db.exec('BEGIN IMMEDIATE');
    try { const result = action(); this.db.exec('COMMIT'); return result; }
    catch (e) { this.db.exec('ROLLBACK'); throw e; }
  }
  managed(threadId: string) { return !!this.db.prepare('SELECT 1 FROM managed WHERE threadId=?').get(threadId); }
  markManaged(threadId: string) { this.db.prepare('INSERT OR IGNORE INTO managed VALUES (?)').run(threadId); }
  settings(threadId: string): ExecutionSettings | undefined {
    const row = this.db.prepare('SELECT settings FROM session_settings WHERE threadId=?').get(threadId);
    return row ? JSON.parse(String(row.settings)) : undefined;
  }
  saveSettings(threadId: string, settings: ExecutionSettings) {
    this.db.prepare('INSERT INTO session_settings VALUES (?,?) ON CONFLICT(threadId) DO UPDATE SET settings=excluded.settings').run(threadId, JSON.stringify(settings));
  }
  runByRequest(id: string) { return this.db.prepare('SELECT * FROM runs WHERE requestId=?').get(id) as Run | undefined; }
  run(id: string) { return this.db.prepare('SELECT * FROM runs WHERE id=?').get(id) as Run | undefined; }
  runs() { return this.db.prepare("SELECT * FROM runs WHERE state IN ('queued','running','waiting_approval','waiting_input','unknown') OR id IN (SELECT id FROM runs ORDER BY createdAt DESC LIMIT 100) ORDER BY createdAt DESC").all() as Run[]; }
  active(threadId: string) { return this.db.prepare("SELECT * FROM runs WHERE threadId=? AND state IN ('queued','running','waiting_approval','waiting_input','unknown') ORDER BY createdAt DESC LIMIT 1").get(threadId) as Run | undefined; }
  createRun(requestId: string, threadId: string, text: string): Run {
    const run = { id: randomUUID(), requestId, threadId, turnId: null, state: 'queued', text, error: null, createdAt: new Date().toISOString() };
    this.db.prepare('INSERT INTO runs VALUES (?, ?, ?, ?, ?, ?, ?, ?)').run(...Object.values(run));
    return run;
  }
  updateRun(id: string, change: Partial<Run>) {
    const run = this.run(id);
    if (!run) return;
    const updated = { ...run, ...change };
    this.transaction(() => {
      this.db.prepare('UPDATE runs SET turnId=?, state=?, error=? WHERE id=?').run(updated.turnId, updated.state, updated.error, id);
      this.insertEvent('run.updated', updated);
    });
    this.emit('event');
  }
  insertEvent(type: string, data: unknown) {
    return this.db.prepare('INSERT INTO events(type,data,createdAt) VALUES (?,?,?)')
      .run(type, JSON.stringify(data), new Date().toISOString()).lastInsertRowid;
  }
  event(type: string, data: unknown) { this.insertEvent(type, data); this.emit('event'); }
  events(after: number, limit = 500): StoredEvent[] {
    return this.db.prepare('SELECT * FROM events WHERE seq>? ORDER BY seq LIMIT ?').all(after, limit).map(row => ({ ...row, data: JSON.parse(row.data as string) })) as StoredEvent[];
  }
  cursor(): number { return Number((this.db.prepare('SELECT COALESCE(MAX(seq),0) AS seq FROM events').get()!).seq); }
  approvals(): Approval[] { return this.db.prepare("SELECT * FROM approvals WHERE state='pending'").all().map(row => ({ ...row, params: JSON.parse(row.params as string) })) as Approval[]; }
  close() { this.db.close(); }
}
