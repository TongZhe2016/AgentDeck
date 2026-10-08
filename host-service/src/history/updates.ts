import type { Codex } from '../providers/codex.js';

/** Read only the open conversation, including changes to its last persisted turn. */
export async function sessionUpdates(codex: Codex, threadId: string, cursor: string | null) {
  // Capture the next anchor before reading updates, so a newly started turn cannot be skipped.
  const tip = await codex.request('thread/turns/list', { threadId, limit: cursor ? 1 : 20, sortDirection: 'desc', itemsView: 'full' });
  if (!cursor) return { turns: tip.data.reverse(), liveCursor: tip.backwardsCursor, more: false };
  const page = await codex.request('thread/turns/list', { threadId, cursor, limit: 20, sortDirection: 'asc', itemsView: 'full' });
  return { turns: page.data, liveCursor: page.nextCursor ?? tip.backwardsCursor, more: !!page.nextCursor };
}
