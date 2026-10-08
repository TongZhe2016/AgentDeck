import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtemp, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { api } from '../src/api/server.js';
import { Codex } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';

test('downloads authenticated local files as bytes without requiring Codex', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'agentdeck-download-'));
  const name = '报告 + data.bin';
  const bytes = Buffer.alloc(2 * 1024 * 1024);
  for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
  await writeFile(join(directory, name), bytes);
  const store = new Store(':memory:');
  const server = api('fixture', new Codex('not-installed'), store);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${(server.address() as any).port}/v1/files?`;
  const headers = { Authorization: 'Bearer fixture' };
  try {
    const query = new URLSearchParams({ cwd: directory, path: name });
    assert.equal((await fetch(base + query)).status, 401);
    const response = await fetch(base + query, { headers });
    assert.equal(response.status, 200);
    assert.equal(Number(response.headers.get('Content-Length')), bytes.length);
    assert.ok(response.headers.get('Content-Disposition')?.includes(encodeURIComponent(name)));
    assert.deepEqual(Buffer.from(await response.arrayBuffer()), bytes);
    const absolute = await fetch(base + new URLSearchParams({ path: join(directory, name) }), { headers });
    assert.equal(absolute.status, 200);
    assert.deepEqual(Buffer.from(await absolute.arrayBuffer()), bytes);
    for (const path of [directory, join(directory, 'missing')]) {
      const invalid = await fetch(base + new URLSearchParams({ path }), { headers });
      assert.equal(invalid.status, 400);
      assert.ok((await invalid.json() as any).error);
    }
    assert.equal((await fetch(base + new URLSearchParams({ path: name }), { headers })).status, 400);
    await writeFile(join(directory, 'empty'), '');
    const empty = await fetch(base + new URLSearchParams({ path: join(directory, 'empty') }), { headers });
    assert.equal(empty.status, 200); assert.equal((await empty.arrayBuffer()).byteLength, 0);
  } finally {
    server.closeAllConnections(); await new Promise<void>(r => server.close(() => r()));
    store.close(); await rm(directory, { recursive: true, force: true });
  }
});
