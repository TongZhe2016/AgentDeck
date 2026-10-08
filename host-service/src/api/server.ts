import { createServer, type IncomingMessage, type ServerResponse } from 'node:http';
import { Coordinator } from '../execution/coordinator.js';
import { Codex } from '../providers/codex.js';
import { Store } from '../storage/store.js';
import * as git from '../git/git.js';
import { Attachments } from '../attachments/attachments.js';
import { Transcription } from '../attachments/transcription.js';
import { search } from '../history/search.js';
import { executionOptions, validateSettings, threadSettings, effectiveSettings } from '../providers/execution-settings.js';

async function body(request: IncomingMessage): Promise<any> {
  const chunks: Buffer[] = []; let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 1024 * 1024) throw new Error('请求超过 1 MiB');
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString() || '{}');
}
function text(value: unknown, name: string): string {
  if (typeof value !== 'string' || !value.trim()) throw new Error(`缺少 ${name}`);
  return value;
}
function json(response: ServerResponse, data: unknown, code = 200) {
  response.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' });
  response.end(JSON.stringify(data));
}

export function api(token: string, codex: Codex, store: Store, attachmentDirectory?: string) {
  const attachments = attachmentDirectory ? new Attachments(store, attachmentDirectory) : undefined;
  const coordinator = new Coordinator(codex, store, attachments);
  const transcription = attachments ? new Transcription(attachments) : undefined;
  return createServer(async (req, res) => {
    if (req.headers.authorization !== `Bearer ${token}`) { json(res, { error: '服务令牌无效' }, 401); return; }
    const url = new URL(req.url!, 'http://127.0.0.1');
    const path = url.pathname;
    try {
      if (path === '/v1/transcriptions' && req.method === 'POST' && transcription) {
        const b = await body(req);
        json(res, await transcription.transcribe(text(b.attachmentId, '录音 ID'), text(b.threadId, '会话 ID'))); return;
      }
      const attachment = path.match(/^\/v1\/attachments\/([^/]+)$/);
      if (attachment && attachments) {
        if (req.method === 'POST') { json(res, await attachments.upload(attachment[1], text(url.searchParams.get('threadId'), '会话 ID'), req)); return; }
        if (req.method === 'DELETE') { await attachments.remove(attachment[1]); json(res, { ok: true }); return; }
        if (req.method === 'GET') { const result = await attachments.download(attachment[1]); res.writeHead(200, { 'Content-Type': result.mime }); res.end(result.bytes); return; }
      }
      if (path === '/v1/health') { json(res, { protocol: 1, version: '0.1.0', platform: process.platform, providers: ['codex'] }); return; }
      if (path === '/v1/snapshot') { json(res, { runs: store.runs(), approvals: store.approvals(), cursor: store.cursor() }); return; }
      if (path === '/v1/events') {
        let cursor = Number(url.searchParams.get('after') ?? req.headers['last-event-id'] ?? 0);
        if (!Number.isSafeInteger(cursor) || cursor < 0 || cursor > store.cursor()) throw new Error('事件游标无效，请重新获取快照');
        res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', 'Connection': 'keep-alive' });
        res.flushHeaders();
        let closed = false;
        const pump = () => {
          if (closed || res.writableNeedDrain) return;
          for (const event of store.events(cursor)) {
            cursor = event.seq;
            if (!res.write(`id: ${event.seq}\ndata: ${JSON.stringify(event)}\n\n`)) return;
          }
          if (cursor < store.cursor()) setImmediate(pump);
        };
        const heartbeat = setInterval(() => { if (!res.writableNeedDrain) res.write(': keepalive\n\n'); }, 15_000);
        store.on('event', pump); res.on('drain', pump);
        res.on('close', () => { closed = true; clearInterval(heartbeat); store.off('event', pump); });
        pump(); return;
      }
      // Git remains usable when Codex is not installed or its API is unavailable.
      const cwd = url.searchParams.get('cwd');
      if (path === '/v1/git/status') { json(res, await git.status(text(cwd, '项目路径'))); return; }
      if (path === '/v1/git/diff') { json(res, await git.diff(text(cwd, '项目路径'), text(url.searchParams.get('path'), '文件路径'),
        url.searchParams.get('group') ?? 'unstaged', url.searchParams.get('commit') ?? undefined, Number(url.searchParams.get('parent') ?? 0))); return; }
      if (path === '/v1/git/graph') { json(res, await git.graph(text(cwd, '项目路径'), url.searchParams.get('scope') ?? 'head', url.searchParams.get('cursor') ?? undefined)); return; }
      if (path === '/v1/git/commit') { json(res, await git.commitDetail(text(cwd, '项目路径'), text(url.searchParams.get('oid'), '提交 ID'), Number(url.searchParams.get('parent') ?? 0))); return; }
      await codex.start();
      if (path === '/v1/models' && req.method === 'GET') { json(res, await executionOptions(codex, url.searchParams.get('cwd') ?? undefined)); return; }
      if (path === '/v1/search' && req.method === 'POST') {
        const b = await body(req); let cancelled = false;
        res.on('close', () => { cancelled = true; });
        const result = await search(codex, text(b.query, '搜索文字'), b.project ?? '', b.cursor, () => cancelled);
        if (!cancelled) json(res, result); return;
      }
      if (path === '/v1/sessions' && req.method === 'GET') {
        const result = await codex.request('thread/list', { limit: 40, sortKey: 'updated_at',
          cursor: url.searchParams.get('cursor'), searchTerm: url.searchParams.get('search'), modelProviders: [], useStateDbOnly: true });
        json(res, { ...result, data: result.data.map((thread: any) => ({ id: thread.id, name: thread.name, preview: thread.preview?.slice(0, 200), cwd: thread.cwd, updatedAt: thread.updatedAt, model: thread.model, reasoningEffort: thread.reasoningEffort, managed: store.managed(thread.id) })) }); return;
      }
      if (path === '/v1/sessions' && req.method === 'POST') {
        const b = await body(req);
        const result = await codex.request('thread/start', { cwd: text(b.cwd, '项目路径'), ...threadSettings() });
        store.markManaged(result.thread.id);
        const settings = await effectiveSettings(codex, result); store.saveSettings(result.thread.id, settings);
        json(res, { ...result.thread, executionSettings: settings, managed: true }); return;
      }
      const settingsRoute = path.match(/^\/v1\/sessions\/([^/]+)\/settings$/);
      if (settingsRoute && req.method === 'POST') {
        const id = decodeURIComponent(settingsRoute[1]);
        if (!store.managed(id)) throw new Error('请先恢复此历史会话');
        if (store.active(id)) throw new Error('请等待本轮执行结束后再修改设置');
        const settings = await validateSettings(codex, await body(req));
        store.saveSettings(id, settings);
        json(res, { executionSettings: settings }); return;
      }
      const session = path.match(/^\/v1\/sessions\/([^/]+)(\/resume)?$/);
      if (session) {
        const id = decodeURIComponent(session[1]);
        if (session[2] && req.method === 'POST') {
          const b = await body(req);
          if (!b.confirmStopped) throw new Error('请先确认原会话已停止');
          if (store.active(id)) throw new Error('此会话已有活动或结果待核实的执行');
          const current = await codex.request('thread/read', { threadId: id, includeTurns: false });
          if (current.thread.status?.type === 'active') throw new Error('此会话仍在运行');
          const result = await codex.request('thread/resume', { threadId: id, excludeTurns: true, ...threadSettings(store.settings(id)) });
          store.markManaged(id);
          const settings = await effectiveSettings(codex, result); store.saveSettings(id, settings);
          json(res, { ...result.thread, executionSettings: settings, managed: true }); return;
        }
        if (req.method === 'GET') {
          const result = await codex.request('thread/read', { threadId: id, includeTurns: false });
          const page = await codex.request('thread/turns/list', { threadId: id, limit: 20, sortDirection: 'desc', itemsView: 'full', cursor: url.searchParams.get('cursor') });
          json(res, { ...result.thread, turns: page.data.reverse(), nextCursor: page.nextCursor, executionSettings: store.settings(id), managed: store.managed(id) }); return;
        }
      }
      if (path === '/v1/runs' && req.method === 'POST') {
        const b = await body(req);
        json(res, await coordinator.start(text(b.clientRequestId, '请求 ID'), text(b.threadId, '会话 ID'), text(b.text, '消息'), b.attachments ?? [])); return;
      }
      const cancel = path.match(/^\/v1\/runs\/([^/]+)\/cancel$/);
      if (cancel && req.method === 'POST') { json(res, await coordinator.cancel(cancel[1])); return; }
      const reconcile = path.match(/^\/v1\/runs\/([^/]+)\/reconcile$/);
      if (reconcile && req.method === 'POST') {
        const run = store.run(reconcile[1]);
        if (!run || run.state !== 'unknown') throw new Error('执行无需核实');
        const result = await codex.request('thread/read', { threadId: run.threadId, includeTurns: true });
        const turn = result.thread.turns.find((t: any) => t.id === run.turnId);
        if (turn && ['completed', 'failed', 'interrupted'].includes(turn.status)) store.updateRun(run.id, { state: turn.status, error: null });
        json(res, store.run(run.id)); return;
      }
      const approval = path.match(/^\/v1\/approvals\/([^/]+)$/);
      if (approval && req.method === 'POST') { const b = await body(req); coordinator.respond(approval[1], b.decision, b.answers); json(res, { ok: true }); return; }
      json(res, { error: '接口不存在' }, 404);
    } catch (e) {
      if (!res.headersSent) json(res, { error: (e as Error).message }, 400);
      else res.end();
    }
  });
}
