import { Codex } from '../providers/codex.js';

type Cursor = { query: string; project: string; listCursor: string | null; loaded: boolean; threads: any[]; turnCursor: string | null };

function searchable(item: any): string {
  switch (item.type) {
    case 'userMessage': return (item.content ?? []).filter((part: any) => part.type === 'text').map((part: any) => part.text).join('\n');
    case 'agentMessage': case 'plan': return item.text ?? '';
    case 'commandExecution': return `${item.command ?? ''}\n${item.aggregatedOutput ?? ''}`;
    case 'fileChange': return (item.changes ?? []).map((change: any) => change.path).join('\n');
    default: return '';
  }
}

// Each page scans at most ten native history pages. The cursor resumes inside a
// long session as well as across session-list pages, so older messages stay reachable.
export async function search(codex: Codex, query: string, project = '', cursor?: string, cancelled = () => false) {
  if (!query.trim()) throw new Error('请输入搜索文字');
  const state: Cursor = cursor ? JSON.parse(Buffer.from(cursor, 'base64url').toString()) :
    { query, project, listCursor: null, loaded: false, threads: [], turnCursor: null };
  if (state.query !== query || state.project !== project) throw new Error('搜索条件已变化，请重新搜索');
  const matches: any[] = []; const needle = query.toLocaleLowerCase(); let scannedPages = 0;
  while (scannedPages < 10 && !cancelled()) {
    if (!state.threads.length) {
      if (state.loaded && !state.listCursor) break;
      const list = await codex.request('thread/list', { limit: 10, cursor: state.listCursor, modelProviders: [], sortKey: 'updated_at' });
      state.loaded = true; state.listCursor = list.nextCursor ?? null;
      state.threads = list.data.filter((thread: any) => !project || thread.cwd === project)
        .map((thread: any) => ({ id: thread.id, cwd: thread.cwd, name: thread.name, preview: (thread.preview ?? '').slice(0, 160) }));
      if (!state.threads.length) { scannedPages++; continue; }
    }
    const thread = state.threads[0];
    const turns = await codex.request('thread/turns/list', { threadId: thread.id, cursor: state.turnCursor, limit: 20, itemsView: 'full', sortDirection: 'desc' });
    scannedPages++;
    for (const turn of turns.data) for (const item of turn.items ?? []) {
      const content = searchable(item); const index = content.toLocaleLowerCase().indexOf(needle);
      if (index >= 0) matches.push({ thread, itemId: item.id, excerpt: content.slice(Math.max(0, index - 80), index + needle.length + 180) });
    }
    state.turnCursor = turns.nextCursor ?? null;
    if (!state.turnCursor) state.threads.shift();
  }
  return { matches, scannedPages, nextCursor: state.threads.length || state.listCursor ? Buffer.from(JSON.stringify(state)).toString('base64url') : null };
}
