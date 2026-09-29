import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Codex } from '../src/providers/codex.js';
import { Store } from '../src/storage/store.js';
import { api } from '../src/api/server.js';
import { once } from 'node:events';

test('loopback API requires token and replays only events after the client cursor', async () => {
  const store = new Store(':memory:'); const codex = new Codex('does-not-exist');
  const server = api('test-token', codex, store);
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  const base = `http://127.0.0.1:${(server.address() as any).port}`;
  try {
    assert.equal((await fetch(base + '/v1/health')).status, 401);
    const headers = { Authorization: 'Bearer test-token' };
    assert.equal((await fetch(base + '/v1/health', { headers })).status, 200);
    store.event('first', { value: 1 }); store.event('second', { value: 2 });
    const abort = new AbortController();
    const response = await fetch(base + '/v1/events?after=1', { headers, signal: abort.signal });
    const { value } = await response.body!.getReader().read();
    const chunk = new TextDecoder().decode(value);
    assert.match(chunk, /"type":"second"/); assert.doesNotMatch(chunk, /"type":"first"/);
    abort.abort();
    store.event('after disconnect', {});
    const snapshot = await (await fetch(base + '/v1/snapshot', { headers })).json() as any;
    assert.equal(snapshot.cursor, 3);
  } finally { server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); store.close(); }
});
