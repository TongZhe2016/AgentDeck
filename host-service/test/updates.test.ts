import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Codex } from '../src/providers/codex.js';
import { sessionUpdates } from '../src/history/updates.js';

test('follow reads only the selected thread and anchors before fetching new turns', async () => {
  const codex = new Codex('unused');
  const calls: any[] = [];
  const pages = [
    { data: [{ id: 'old' }], backwardsCursor: 'old-anchor' },
    { data: [{ id: 'new' }], backwardsCursor: 'new-anchor' },
    { data: [{ id: 'old', items: ['final reply'] }, { id: 'new' }], nextCursor: null },
    { data: [{ id: 'latest' }], backwardsCursor: 'latest-anchor' },
    { data: [{ id: 'new' }, { id: 'page-end' }], nextCursor: 'continue' },
  ];
  codex.request = async (method, params) => { calls.push({ method, params }); return pages.shift(); };
  assert.equal((await sessionUpdates(codex, 'selected', null)).liveCursor, 'old-anchor');
  const update = await sessionUpdates(codex, 'selected', 'old-anchor');
  assert.deepEqual(update.turns.map((t: any) => t.id), ['old', 'new']);
  assert.equal(update.liveCursor, 'new-anchor');
  assert.equal(update.more, false);
  assert.deepEqual(calls.slice(1, 3).map(c => c.params.sortDirection), ['desc', 'asc']);
  assert.equal(calls[2].params.cursor, 'old-anchor');
  const backlog = await sessionUpdates(codex, 'selected', 'new-anchor');
  assert.equal(backlog.liveCursor, 'continue'); assert.equal(backlog.more, true);
  assert.ok(calls.every(c => c.method === 'thread/turns/list' && c.params.threadId === 'selected' && c.params.limit <= 20));
});
