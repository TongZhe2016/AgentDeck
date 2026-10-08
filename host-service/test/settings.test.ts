import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Codex } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { api } from '../src/api/server.js';
import { Coordinator } from '../src/execution/coordinator.js';

class SettingsCodex extends Codex {
  calls: { method: string; params: any }[] = [];
  loaded = false;
  active = false;
  override async start() {}
  override async request(method: string, params: any): Promise<any> {
    this.calls.push({ method, params });
    const model = (name: string, efforts: string[], isDefault = false) => ({ id: name, model: name, displayName: name,
      supportedReasoningEfforts: efforts.map(reasoningEffort => ({ reasoningEffort })), defaultReasoningEffort: 'medium', isDefault });
    if (method === 'model/list') return params.cursor
      ? { data: [model('gpt-6-astra', ['medium', 'high', 'max'])], nextCursor: null }
      : { data: [model('gpt-6.1-sol', ['low', 'medium', 'high'], true)], nextCursor: 'models-next' };
    if (method === 'config/read') return { config: { model: 'gpt-6.1-sol', model_reasoning_effort: 'high', private_setting: 'never expose' } };
    if (method === 'thread/list') return { data: [{ id: 'thread', name: 'Title', cwd: '/project', preview: 'A'.repeat(1000), turns: [{ items: ['large output'] }] }], nextCursor: 'threads-next' };
    if (method === 'thread/read') return { thread: { id: 'thread', cwd: '/project', status: { type: this.active ? 'active' : 'idle' } } };
    if (method === 'thread/loaded/list') return { data: this.loaded ? ['thread'] : [] };
    if (method === 'thread/start' || method === 'thread/resume') return {
      thread: { id: 'thread', cwd: '/project' }, model: params.model ?? 'gpt-6.1-sol', reasoningEffort: params.config?.model_reasoning_effort ?? null,
      approvalPolicy: params.approvalPolicy,
      sandbox: { type: params.sandbox === 'danger-full-access' ? 'dangerFullAccess' : params.sandbox === 'read-only' ? 'readOnly' : 'workspaceWrite' },
    };
    if (method === 'turn/start') return { turn: { id: 'turn' } };
    throw new Error(`Unexpected RPC: ${method}`);
  }
}

test('API lists models, saves validated settings, and lists summaries without reading conversations', async () => {
  const store = new Store(':memory:'); const codex = new SettingsCodex(); const server = api('test', codex, store);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${(server.address() as any).port}/v1/`;
  const request = async (path: string, body?: unknown) => {
    const response = await fetch(base + path, { headers: { Authorization: 'Bearer test' }, ...(body ? { method: 'POST', body: JSON.stringify(body) } : {}) });
    return { status: response.status, data: await response.json() as any };
  };
  try {
    const sessions = await request('sessions');
    assert.equal(sessions.data.nextCursor, 'threads-next');
    assert.equal(sessions.data.data[0].turns, undefined);
    assert.equal(sessions.data.data[0].preview.length, 200);
    assert.deepEqual(codex.calls.map(c => c.method), ['thread/list']);
    assert.equal(codex.calls[0].params.useStateDbOnly, true);
    const catalog = await request('models?cwd=/project');
    assert.equal(catalog.data.data.length, 2);
    assert.equal(catalog.data.defaults.effort, 'high');
    assert.equal(JSON.stringify(catalog.data).includes('never expose'), false);
    const created = await request('sessions', { cwd: '/project' });
    assert.deepEqual(created.data.executionSettings, { model: 'gpt-6.1-sol', effort: 'medium', permissionMode: 'on-request' });
    const settings = { model: 'gpt-6-astra', effort: 'max', permissionMode: 'full-access' };
    const saved = await request('sessions/thread/settings', settings);
    assert.equal(saved.status, 200);
    assert.deepEqual(saved.data.executionSettings, settings);
    assert.equal(codex.calls.some(c => c.method === 'thread/read' || c.method === 'thread/resume'), false,
      'changing next-turn settings must also work before an empty thread has a rollout');
    const invalid = await request('sessions/thread/settings', { ...settings, effort: 'low' });
    assert.equal(invalid.status, 400);
    assert.deepEqual(store.settings('thread'), settings);
    store.createRun('active', 'thread', 'hello');
    assert.equal((await request('sessions/thread/settings', settings)).status, 400);
  } finally { server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); store.close(); }
});

test('settings survive restart and control both reloaded and already loaded turns', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'agentdeck-settings-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  let store = new Store(join(directory, 'db'));
  store.markManaged('thread');
  store.saveSettings('thread', { model: 'gpt-6-astra', effort: 'max', permissionMode: 'full-access' });
  store.close(); store = new Store(join(directory, 'db'));
  const codex = new SettingsCodex(); const coordinator = new Coordinator(codex, store);
  try {
    const run = await coordinator.start('one', 'thread', 'hello');
    assert.equal(run.state, 'running');
    let turn = codex.calls.find(c => c.method === 'turn/start')!.params;
    assert.equal(turn.model, 'gpt-6-astra'); assert.equal(turn.effort, 'max');
    assert.equal(turn.approvalPolicy, 'never'); assert.equal(turn.sandboxPolicy.type, 'dangerFullAccess');
    store.updateRun(run.id, { state: 'completed' });
    codex.loaded = true;
    store.saveSettings('thread', { model: 'gpt-6.1-sol', effort: 'low', permissionMode: 'untrusted' });
    const second = await coordinator.start('two', 'thread', 'next');
    turn = codex.calls.filter(c => c.method === 'turn/start').at(-1)!.params;
    assert.equal(turn.approvalPolicy, 'untrusted'); assert.equal(turn.sandboxPolicy.type, 'workspaceWrite');
    assert.equal(turn.model, 'gpt-6.1-sol'); assert.equal(turn.effort, 'low');
    assert.equal(codex.calls.filter(c => c.method === 'thread/resume').length, 1);
    store.updateRun(second.id, { state: 'completed' });
    store.saveSettings('thread', { model: 'gpt-6.1-sol', effort: 'high', permissionMode: 'never' });
    await coordinator.start('three', 'thread', 'without approvals');
    turn = codex.calls.filter(c => c.method === 'turn/start').at(-1)!.params;
    assert.equal(turn.approvalPolicy, 'never');
    assert.equal(turn.sandboxPolicy.type, 'workspaceWrite', 'disabling approvals must preserve the workspace sandbox');
  } finally { store.close(); }
});

test('external history resumes in place, accepts settings, and refuses active history', async () => {
  const store = new Store(':memory:'); const codex = new SettingsCodex(); const server = api('test', codex, store);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${(server.address() as any).port}/v1/`;
  const post = async (path: string, body: unknown) => {
    const response = await fetch(base + path, { method: 'POST', headers: { Authorization: 'Bearer test' }, body: JSON.stringify(body) });
    return { status: response.status, data: await response.json() as any };
  };
  try {
    codex.active = true;
    assert.equal((await post('sessions/thread/resume', { confirmStopped: true })).status, 400);
    assert.equal(store.managed('thread'), false);
    assert.equal(codex.calls.some(c => c.method === 'thread/resume'), false);
    codex.active = false;
    const resumed = await post('sessions/thread/resume', { confirmStopped: true });
    assert.equal(resumed.status, 200);
    assert.equal(resumed.data.id, 'thread');
    assert.equal(resumed.data.managed, true);
    const settings = { model: 'gpt-6-astra', effort: 'max', permissionMode: 'read-only' };
    assert.equal((await post('sessions/thread/settings', settings)).status, 200);
    codex.loaded = true;
    const run = await post('runs', { clientRequestId: 'continue', threadId: 'thread', text: 'Continue original conversation' });
    assert.equal(run.status, 200);
    assert.equal(run.data.state, 'running');
    const turn = codex.calls.find(c => c.method === 'turn/start')!.params;
    assert.equal(turn.threadId, 'thread');
    assert.equal(turn.model, settings.model);
    assert.equal(turn.effort, settings.effort);
    assert.equal(turn.sandboxPolicy.type, 'readOnly');
    assert.equal(codex.calls.some(c => c.method === 'thread/start'), false);
  } finally { server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); store.close(); }
});
