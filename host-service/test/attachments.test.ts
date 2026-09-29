import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import { Codex } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { api } from '../src/api/server.js';

class FixtureCodex extends Codex {
  inputs: any[] = [];
  override async start() {}
  override async request(method: string, params: any) {
    if (method === 'thread/loaded/list') return { data: ['thread'] };
    if (method === 'turn/start') { this.inputs.push(params.input); return { turn: { id: 'turn' } }; }
    return {};
  }
}

test('uploaded attachment is bound to its session and included once in a retried run', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-upload-'));
  const store = new Store(':memory:'); store.markManaged('thread');
  const codex = new FixtureCodex(); const server = api('test-token', codex, store, dir);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${(server.address() as any).port}/v1`;
  const headers = { Authorization: 'Bearer test-token' };
  const image = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/l9sAAAAASUVORK5CYII=', 'base64');
  try {
    for (let i = 0; i < 2; i++) assert.equal((await fetch(base + '/attachments/image?threadId=thread', {
      method: 'POST', headers: { ...headers, 'Content-Type': 'image/png' }, body: image,
    })).status, 200);
    assert.equal((await fetch(base + '/attachments/image?threadId=other', {
      method: 'POST', headers: { ...headers, 'Content-Type': 'image/png' }, body: image,
    })).status, 400);
    const run = { clientRequestId: 'id', threadId: 'thread', text: 'Describe the image', attachments: ['image'] };
    for (let i = 0; i < 2; i++) assert.equal((await fetch(base + '/runs', {
      method: 'POST', headers: { ...headers, 'Content-Type': 'application/json' }, body: JSON.stringify(run),
    })).status, 200);
    assert.equal(codex.inputs.length, 1); assert.equal(codex.inputs[0][1].type, 'localImage');
    assert.equal((await fetch(base + '/attachments/image', { method: 'DELETE', headers })).status, 400);
    assert.equal((await fetch(base + '/attachments/image', { headers })).status, 200);
  } finally {
    server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); store.close(); rmSync(dir, { recursive: true, force: true });
  }
});
