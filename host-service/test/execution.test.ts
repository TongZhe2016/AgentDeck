import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Codex } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { Coordinator } from '../src/execution/coordinator.js';

class FakeCodex extends Codex {
  starts = 0;
  replies: any[] = [];
  override async request(method: string) {
    if (method === 'thread/loaded/list') return { data: ['thread'] };
    if (method === 'turn/start') { this.starts++; return { turn: { id: 'turn-1' } }; }
    return {};
  }
  override reply(id: string | number, result: unknown) { this.replies.push({ id, result }); }
}

test('duplicate submission, exclusive ownership, disconnect-independent completion, and stale approvals', async () => {
  const store = new Store(':memory:'); const codex = new FakeCodex(); const coordinator = new Coordinator(codex, store);
  store.markManaged('thread');
  const [run, repeated] = await Promise.all([coordinator.start('request', 'thread', 'hello'), coordinator.start('request', 'thread', 'hello')]);
  assert.equal(run.id, repeated.id); assert.equal(codex.starts, 1);
  await assert.rejects(coordinator.start('new-request', 'thread', 'other'), /已有活动/);
  codex.emit('request', { id: 10, method: 'item/commandExecution/requestApproval', params: { threadId: 'thread', turnId: 'turn-1', command: 'ls' } });
  const approval = store.approvals()[0];
  coordinator.respond(String(approval.id), 'accept');
  assert.equal(codex.replies.length, 1);
  assert.throws(() => coordinator.respond(String(approval.id), 'accept'), /已解决/);
  // There is deliberately no phone/SSE observer attached.
  codex.emit('notification', { method: 'turn/completed', params: { threadId: 'thread', turn: { id: 'turn-1', status: 'completed' } } });
  assert.equal(store.run(run.id)?.state, 'completed');
  assert.ok(store.events(0).some(e => e.type === 'approval.resolved'));
  const cursor = store.events(0)[1].seq;
  assert.ok(store.events(cursor).every(e => e.seq > cursor));
  store.close();
});

test('restart retains request deduplication and marks in-flight work unknown without replay', async t => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-db-')); t.after(() => rmSync(dir, { recursive: true, force: true }));
  const path = join(dir, 'db'); let store = new Store(path);
  store.markManaged('thread'); const codex = new FakeCodex();
  const run = await new Coordinator(codex, store).start('request', 'thread', 'hello');
  store.close(); store = new Store(path);
  assert.equal(store.run(run.id)?.state, 'unknown');
  await new Coordinator(codex, store).start('request', 'thread', 'hello');
  assert.equal(codex.starts, 1); store.close();
});
