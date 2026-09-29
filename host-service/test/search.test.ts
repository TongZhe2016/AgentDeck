import { test } from 'node:test';
import assert from 'node:assert/strict';
import { Codex } from '../src/providers/codex.js';
import { search } from '../src/history/search.js';

class HistoryFixture extends Codex {
  calls = 0;
  override async request(method: string, params: any) {
    this.calls++;
    if (method === 'thread/list') return { data: [{ id: 'thread', cwd: '/project', preview: 'test' }], nextCursor: null };
    assert.equal(method, 'thread/turns/list');
    const page = Number(params.cursor ?? 0);
    return { data: [{ items: [{ id: `item-${page}`, type: 'agentMessage', text: `中文 src/main.kt codeSnippet page ${page}` },
      { id: 'hidden', type: 'reasoning', text: 'private thought' }] }], nextCursor: page < 11 ? String(page + 1) : null };
  }
}

test('body search resumes inside long native sessions and excludes internal reasoning', async () => {
  const codex = new HistoryFixture();
  for (const query of ['中文', 'MAIN.kt', 'codeSnippet']) {
    const first = await search(codex, query); assert.equal(first.matches.length, 10); assert.ok(first.nextCursor);
    const second = await search(codex, query, '', first.nextCursor!); assert.equal(second.matches.length, 2); assert.equal(second.nextCursor, null);
    assert.equal(new Set([...first.matches, ...second.matches].map(m => m.itemId)).size, 12);
  }
  assert.equal((await search(codex, 'private')).matches.length, 0);
  const count = codex.calls;
  await search(codex, 'cancel', '', undefined, () => true); assert.equal(codex.calls, count);
});
