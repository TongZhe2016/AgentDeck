import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { Codex, RpcError } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { api } from '../src/api/server.js';

class WriterCodex extends Codex {
  calls: { method: string; params: any }[] = [];
  loaded: string[] = [];
  blocked = true;
  failure?: string;
  override async start() {}
  override async request(method: string, params: any): Promise<any> {
    this.calls.push({ method, params });
    if (method === 'thread/loaded/list') return { data: this.loaded, nextCursor: null };
    if (method === 'thread/read') return { thread: { id: params.threadId, cwd: '/fixture', status: { type: 'notLoaded' } } };
    if (method === 'thread/turns/list') return { data: [], nextCursor: null };
    if (method === 'thread/resume') {
      if (this.failure) throw new RpcError(this.failure);
      if (this.blocked) throw new RpcError(`thread ${params.threadId} already has an active writer`);
      this.loaded.push(params.threadId);
    }
    if (method === 'thread/resume' || method === 'thread/fork') {
      return { thread: { id: method === 'thread/fork' ? 'forked' : params.threadId, cwd: '/fixture', forkedFromId: method === 'thread/fork' ? params.threadId : null },
        model: 'gpt-6.1-sol', reasoningEffort: 'high', approvalPolicy: params.approvalPolicy, sandbox: { type: 'workspaceWrite' } };
    }
    throw new Error(`Unexpected method ${method}`);
  }
}

test('external idle writer hides editing before resume; takeover failure and fork remain distinct', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-writer-'));
  mkdirSync(join(dir, 'thread-writer-locks')); writeFileSync(join(dir, 'thread-writer-locks', 'original.lock'), '');
  const codex = new WriterCodex('unused', dir); const store = new Store(':memory:');
  const server = api('fixture', codex, store);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const request = async (path: string, post = false) => {
    const res = await fetch(`http://127.0.0.1:${(server.address() as any).port}/v1/sessions/${path}`, {
      method: post ? 'POST' : 'GET', headers: { Authorization: 'Bearer fixture' }, ...(post ? { body: '{}' } : {}),
    });
    return { status: res.status, body: await res.json() as any };
  };
  try {
    const read = await request('original');
    assert.equal(read.body.writerState, 'external');
    assert.equal(read.body.status.type, 'notLoaded');
    assert.equal(codex.calls.some(c => c.method === 'thread/resume'), false, 'reading must not claim writing');
    const blocked = await request('original/takeover', true);
    assert.equal(blocked.status, 200);
    assert.equal(blocked.body.acquired, false);
    assert.equal(blocked.body.message, '有其他用户正在使用');
    assert.equal(store.managed('original'), false);
    assert.equal(store.settings('original'), undefined);
    assert.equal(existsSync(join(dir, 'thread-writer-locks', 'original.lock')), true, 'native writer locks remain untouched');
    const forked = await request('original/fork', true);
    assert.equal(forked.body.id, 'forked');
    assert.equal(forked.body.forkedFromId, 'original');
    assert.equal(forked.body.writerState, 'owned');
    assert.equal(store.managed('forked'), true);
    assert.equal(store.managed('original'), false);
    assert.equal(codex.calls.find(c => c.method === 'thread/fork')!.params.excludeTurns, true);
    codex.blocked = false;
    const acquired = await request('original/takeover', true);
    assert.equal(acquired.body.acquired, true);
    assert.equal(acquired.body.thread.id, 'original');
    assert.equal(acquired.body.thread.writerState, 'owned');
    assert.equal(store.managed('original'), true);
    assert.equal((await request('original')).body.writerState, 'owned', 'our loaded writer must not hide the composer');
    codex.failure = 'Model provider unavailable';
    const failed = await request('original/takeover', true);
    assert.equal(failed.status, 400);
    assert.equal(failed.body.error, 'Model provider unavailable', 'unrelated failures are not occupancy errors');
  } finally {
    server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); store.close(); rmSync(dir, { recursive: true, force: true });
  }
});

test('writer status handles loaded pagination and available histories without acquiring locks', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-writer-'));
  const codex = new Codex('unused', dir);
  const calls: any[] = [];
  codex.request = async (method, params: any) => { calls.push({ method, params }); return params.cursor ? { data: ['owned'], nextCursor: null } : { data: ['other'], nextCursor: 'next' }; };
  try {
    assert.equal(await codex.writerState('owned'), 'owned');
    assert.equal(await codex.writerState('available'), 'available');
    assert.equal(calls.length, 4);
    assert.ok(calls.every(c => c.method === 'thread/loaded/list'));
    assert.equal(existsSync(join(dir, 'thread-writer-locks')), false);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});
