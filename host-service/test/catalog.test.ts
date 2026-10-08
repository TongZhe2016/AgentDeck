import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';
import { Codex } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { SessionCatalog } from '../src/history/catalog.js';
import { api } from '../src/api/server.js';

class CatalogCodex extends Codex {
  threads = Array.from({ length: 145 }, (_, i) => ({ id: `thread-${String(i).padStart(3, '0')}`, name: `Title ${i}`, cwd: '/fixture', updatedAt: 1000 - i,
    preview: 'P'.repeat(400), turns: [{ items: ['must not cross the API'] }] }));
  calls: any[] = [];
  failPage = false;
  override async start() {}
  override async request(method: string, params: any) {
    assert.equal(method, 'thread/list', 'catalog must never read or resume conversation bodies');
    assert.equal(params.useStateDbOnly, true);
    assert.ok(params.sourceKinds.includes('appServer'));
    assert.ok(params.sourceKinds.includes('cli'));
    this.calls.push(params);
    if (this.failPage && params.cursor) throw new Error('temporary failure');
    const offset = Number(params.cursor ?? 0);
    const sorted = [...this.threads].sort((a, b) => b.updatedAt - a.updatedAt || b.id.localeCompare(a.id));
    return { data: sorted.slice(offset, offset + params.limit), nextCursor: offset + params.limit < sorted.length ? String(offset + params.limit) : null };
  }
}

test('catalog persists all summaries; HTTP pagination/search use cache and replay only directory changes', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-catalog-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  let store = new Store(join(dir, 'db'));
  const codex = new CatalogCodex();
  let catalog = new SessionCatalog(codex, store);
  await catalog.refresh(true);
  assert.equal(catalog.status().total, 145);
  assert.equal(codex.calls.length, 2);
  const first = await catalog.list(null);
  assert.equal(first.data.length, 40); assert.equal(first.data[0].preview.length, 200);
  assert.equal('turns' in first.data[0], false);
  const ids = first.data.map(t => t.id);
  let cursor = first.nextCursor;
  while (cursor) { const page = await catalog.list(cursor); ids.push(...page.data.map(t => t.id)); cursor = page.nextCursor; }
  assert.equal(new Set(ids).size, 145);
  assert.equal((await catalog.list(null, 'Title 144')).data.length, 1);
  const sequence = first.catalogCursor;
  codex.threads[144].name = 'Renamed old conversation';
  codex.threads = codex.threads.filter(t => t.id !== 'thread-001');
  await catalog.refresh(true);
  store.event('agent.event', { privateOutput: 'not directory metadata' });
  const changed = await catalog.list(null, '', sequence);
  assert.deepEqual(changed.changes.removed, ['thread-001']);
  assert.equal(changed.changes.upserted.length, 1);
  assert.equal(changed.changes.upserted[0].name, 'Renamed old conversation');
  assert.equal((await catalog.list(null, '', changed.catalogCursor)).changes.upserted.length, 0);
  catalog.close(); store.close();
  store = new Store(join(dir, 'db')); catalog = new SessionCatalog(codex, store);
  const callsBefore = codex.calls.length;
  const server = api('fixture', codex, store, undefined, catalog);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  try {
    const response = await fetch(`http://127.0.0.1:${(server.address() as any).port}/v1/sessions`, { headers: { Authorization: 'Bearer fixture' } });
    assert.equal(response.status, 200);
    assert.equal((await response.json() as any).catalog.total, 144);
    assert.equal(codex.calls.length, callsBefore, 'phone connection and server restart use the persisted catalog');
  } finally { server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); catalog.close(); store.close(); }
});

test('background polling discovers another client without a phone or RPC notification', async () => {
  const store = new Store(':memory:'); const codex = new CatalogCodex(); const catalog = new SessionCatalog(codex, store);
  try {
    const initialized = once(store, 'event'); catalog.start(); await initialized;
    const cursor = store.cursor(); const calls = codex.calls.length;
    codex.threads.unshift({ id: 'external', name: 'Created in desktop', cwd: '/other', updatedAt: 1010, preview: 'hello', turns: [] });
    const discovered = once(store, 'event');
    await Promise.race([discovered, delay(5000).then(() => { throw new Error('background discovery timed out'); })]);
    const events = store.events(cursor);
    assert.equal(events.length, 1);
    assert.equal(events[0].data.upserted[0].id, 'external');
    assert.equal(codex.calls.length - calls, 1, 'recent update must stop before re-reading older pages');
    const sequence = store.cursor();
    await catalog.refresh(false);
    assert.equal(store.cursor(), sequence, 'unchanged summaries must not emit duplicates');
  } finally { catalog.close(); store.close(); }
});

test('incomplete scans retain the last complete catalog; full reconciliation removes archived threads', async () => {
  const store = new Store(':memory:'); const codex = new CatalogCodex(); const catalog = new SessionCatalog(codex, store);
  try {
    await catalog.refresh(true);
    const before = store.cursor();
    codex.threads.shift(); codex.failPage = true;
    await assert.rejects(catalog.refresh(true), /temporary failure/);
    assert.equal(catalog.status().total, 145); assert.equal(store.cursor(), before);
    codex.failPage = false; await catalog.refresh(true);
    assert.equal(catalog.status().total, 144);
    assert.deepEqual(store.events(before)[0].data.removed, ['thread-000']);
    const reset = await catalog.list(null, '', store.cursor() + 1);
    assert.equal(reset.reset, true);
  } finally { catalog.close(); store.close(); }
});
