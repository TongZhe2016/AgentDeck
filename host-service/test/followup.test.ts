import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Codex, RpcError } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { Coordinator } from '../src/execution/coordinator.js';

class FollowupCodex extends Codex {
  calls: { method: string; params: any }[] = [];
  queue: any[] = [];
  turns: any[] = [];
  loaded = true;
  active = false;
  queueTimeout = false;
  steerFailure?: Error;
  override async request(method: string, params: any): Promise<any> {
    this.calls.push({ method, params });
    if (method === 'thread/loaded/list') return { data: this.loaded ? ['thread'] : [] };
    if (method === 'thread/read') return { thread: { status: { type: this.active ? 'active' : 'idle' } } };
    if (method === 'thread/resume') { this.loaded = true; return { thread: { id: 'thread' }, model: 'host-model', reasoningEffort: 'high', sandbox: { type: 'readOnly' } }; }
    if (method === 'model/list') return { data: [{ model: 'host-model', supportedReasoningEfforts: [{ reasoningEffort: 'high' }], defaultReasoningEffort: 'high' }] };
    if (method === 'turn/start') { this.active = true; return { turn: { id: 'first-turn' } }; }
    if (method === 'turn/steer') {
      if (this.steerFailure) throw this.steerFailure;
      this.message(params.clientUserMessageId, params.expectedTurnId);
      return { turnId: params.expectedTurnId };
    }
    if (method === 'thread/queue/add') {
      const queuedSubmission = { id: `native-${params.clientUserMessageId}`, ...params };
      this.queue.push(queuedSubmission);
      if (this.queueTimeout) throw new Error('thread/queue/add 响应超时');
      return { queuedSubmission };
    }
    if (method === 'thread/queue/list') return { data: this.queue, nextCursor: null };
    if (method === 'thread/queue/delete') { this.queue = this.queue.filter(q => q.id !== params.queuedSubmissionId); return {}; }
    if (method === 'thread/queue/start') { const queued = this.queue.shift()!; this.message(queued.clientUserMessageId, 'resumed-turn'); this.active = true; return { turn: { id: 'resumed-turn' } }; }
    if (method === 'thread/turns/list') return { data: this.turns, nextCursor: null };
    if (method === 'turn/interrupt') return {};
    throw new Error(`Unexpected ${method}`);
  }
  message(clientId: string, turnId: string) {
    this.emit('notification', { method: 'item/completed', params: { threadId: 'thread', turnId, item: { type: 'userMessage', id: `item-${clientId}`, clientId, content: [{ type: 'text', text: clientId }] } } });
  }
  complete(turnId: string) {
    this.active = false;
    this.emit('notification', { method: 'turn/completed', params: { threadId: 'thread', turn: { id: turnId, status: 'completed' } } });
  }
}

test('steer injects into the active turn without taking its execution or approval ownership', async () => {
  const store = new Store(':memory:'); store.markManaged('thread');
  const codex = new FollowupCodex(); const coordinator = new Coordinator(codex, store);
  try {
    const first = await coordinator.start('first', 'thread', 'First', [], 'queue');
    assert.equal(first.mode, 'start', 'an idle thread starts normally regardless of follow-up preference');
    const steer = await coordinator.start('steer', 'thread', 'Change direction', [], 'steer');
    assert.equal(steer.state, 'steered'); assert.equal(steer.turnId, first.turnId);
    assert.equal(store.active('thread')!.id, first.id);
    assert.deepEqual(codex.calls.find(c => c.method === 'turn/steer')!.params, { threadId: 'thread', expectedTurnId: first.turnId, clientUserMessageId: 'steer', input: [{ type: 'text', text: 'Change direction' }] });
    assert.equal((await coordinator.start('steer', 'thread', 'Change direction', [], 'queue')).id, steer.id);
    assert.equal(codex.calls.filter(c => c.method === 'turn/steer').length, 1);
    codex.emit('request', { id: 11, method: 'item/commandExecution/requestApproval', params: { threadId: 'thread', turnId: first.turnId } });
    assert.equal(store.approvals()[0].runId, first.id);
    codex.complete(first.turnId!);
    assert.equal(store.run(first.id)!.state, 'completed');
    assert.equal(store.run(steer.id)!.state, 'steered');
    assert.equal(codex.calls.filter(c => c.method === 'turn/start').length, 1);
  } finally { store.close(); }
});

test('native queued submissions wait, can be removed, and become independent runs by client message ID', async () => {
  const store = new Store(':memory:'); store.markManaged('thread');
  const codex = new FollowupCodex(); const coordinator = new Coordinator(codex, store);
  try {
    const first = await coordinator.start('first', 'thread', 'First');
    const next = await coordinator.start('next', 'thread', 'Next', [], 'queue');
    const removed = await coordinator.start('removed', 'thread', 'Remove this', [], 'queue');
    assert.equal(next.state, 'waiting_queue'); assert.equal(next.queueId, 'native-next');
    assert.equal(store.active('thread')!.id, first.id);
    assert.deepEqual(codex.queue.map(q => q.clientUserMessageId), ['next', 'removed']);
    await coordinator.cancel(removed.id);
    assert.equal(store.run(removed.id)!.state, 'interrupted');
    assert.deepEqual(codex.queue.map(q => q.clientUserMessageId), ['next']);
    assert.equal(codex.calls.some(c => c.method === 'turn/interrupt'), false);
    codex.complete(first.turnId!);
    codex.queue.shift(); codex.message('next', 'next-turn');
    assert.equal(store.active('thread')!.id, next.id);
    codex.complete('next-turn');
    assert.equal(store.run(next.id)!.state, 'completed');
    assert.equal(store.run(next.id)!.turnId, 'next-turn');
    assert.equal(codex.calls.filter(c => c.method === 'turn/start').length, 1, 'Codex owns automatic queue dispatch');
  } finally { store.close(); }
});

test('uncertain follow-ups reconcile against native queue or history without replay, and rejected steering is not restarted', async () => {
  const store = new Store(':memory:'); store.markManaged('thread');
  const codex = new FollowupCodex(); const coordinator = new Coordinator(codex, store);
  try {
    const first = await coordinator.start('first', 'thread', 'First');
    codex.queueTimeout = true;
    const queued = await coordinator.start('queued', 'thread', 'Later', [], 'queue');
    assert.equal(queued.state, 'unknown'); assert.equal(store.active('thread')!.id, first.id);
    assert.equal((await coordinator.start('queued', 'thread', 'Later', [], 'queue')).state, 'unknown');
    assert.equal(codex.calls.filter(c => c.method === 'thread/queue/add').length, 1);
    assert.equal((await coordinator.reconcile(queued.id))!.state, 'waiting_queue');
    codex.steerFailure = new Error('steer timeout');
    const steer = await coordinator.start('steer', 'thread', 'Correction', [], 'steer');
    assert.equal(steer.state, 'unknown'); assert.equal(store.active('thread')!.id, first.id);
    codex.turns = [{ id: first.turnId, status: 'inProgress', items: [{ type: 'userMessage', clientId: 'steer' }] }];
    assert.equal((await coordinator.reconcile(steer.id))!.state, 'steered');
    codex.steerFailure = new RpcError('turn mismatch');
    const rejected = await coordinator.start('rejected', 'thread', 'Too late', [], 'steer');
    assert.equal(rejected.state, 'failed');
    assert.equal(codex.calls.filter(c => c.method === 'turn/start').length, 1);
    const lostStart = store.createRun('lost-start', 'thread', 'Reply lost');
    store.updateRun(lostStart.id, { state: 'unknown' });
    codex.turns = [{ id: 'recorded-turn', status: 'completed', items: [{ type: 'userMessage', clientId: 'lost-start' }] }];
    assert.equal((await coordinator.reconcile(lostStart.id))!.state, 'completed');
  } finally { store.close(); }
});

test('existing databases upgrade in place; restart reconciles preserved native queues before explicit resume', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-followup-')); const path = join(dir, 'db');
  const old = new DatabaseSync(path);
  old.exec("CREATE TABLE runs (id TEXT PRIMARY KEY, requestId TEXT UNIQUE NOT NULL, threadId TEXT NOT NULL, turnId TEXT, state TEXT NOT NULL, text TEXT NOT NULL, error TEXT, createdAt TEXT NOT NULL); INSERT INTO runs VALUES ('old','old','old-thread','old-turn','completed','old text',NULL,'2026-10-01');"); old.close();
  let store = new Store(path); const codex = new FollowupCodex();
  try {
    assert.equal(store.run('old')!.mode, 'start'); assert.equal(store.run('old')!.queueId, null);
    store.markManaged('thread');
    let coordinator = new Coordinator(codex, store);
    const first = await coordinator.start('first', 'thread', 'First');
    const queued = await coordinator.start('queued', 'thread', 'Next', [], 'queue');
    codex.removeAllListeners(); store.close(); store = new Store(path);
    coordinator = new Coordinator(codex, store);
    assert.equal(store.run(first.id)!.state, 'unknown'); assert.equal(store.run(queued.id)!.state, 'unknown');
    codex.turns = [{ id: first.turnId, status: 'interrupted', items: [] }];
    await coordinator.reconcile(first.id); await coordinator.reconcile(queued.id);
    assert.equal(store.run(queued.id)!.state, 'waiting_queue');
    codex.loaded = false; codex.active = false;
    await coordinator.resumeQueue('thread');
    assert.equal(store.run(queued.id)!.state, 'running');
    assert.equal(store.run(queued.id)!.turnId, 'resumed-turn');
    assert.equal(codex.calls.filter(c => c.method === 'thread/queue/add').length, 1);
  } finally { store.close(); rmSync(dir, { recursive: true, force: true }); }
});
