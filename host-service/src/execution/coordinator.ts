import { randomUUID } from 'node:crypto';
import { Codex, RpcError, type RpcMessage } from '../providers/codex.js';
import { Store } from '../storage/store.js';
import { Attachments } from '../attachments/attachments.js';
import { threadSettings, turnSettings, effectiveSettings } from '../providers/execution-settings.js';

const approvalMethods = new Set(['item/commandExecution/requestApproval', 'item/fileChange/requestApproval', 'item/tool/requestUserInput']);
export class Coordinator {
  private pending = new Map<string, { rpcId: string | number; runId: string; method: string }>();
  constructor(readonly codex: Codex, readonly store: Store, private attachments?: Attachments) {
    codex.on('notification', (message: RpcMessage) => this.notification(message));
    codex.on('request', (message: RpcMessage) => this.approval(message));
    codex.on('stopped', (reason: string) => {
      for (const run of store.runs()) if (['queued', 'sending', 'waiting_queue', 'running', 'waiting_approval', 'waiting_input'].includes(run.state)) {
        store.updateRun(run.id, { state: 'unknown', error: reason });
      }
      this.expireApprovals();
    });
  }

  private notification(message: RpcMessage) {
    const p = message.params ?? {};
    if (message.method === 'item/completed' && p.item?.type === 'userMessage' && p.item.clientId) {
      const submitted = this.store.runByRequest(p.item.clientId);
      if (submitted && submitted.threadId === p.threadId && ['sending', 'waiting_queue', 'unknown'].includes(submitted.state)) {
        this.store.updateRun(submitted.id, { turnId: p.turnId, state: submitted.mode === 'steer' ? 'steered' : 'running', error: null });
      }
    }
    const run = p.threadId ? this.store.active(p.threadId) : undefined;
    if (!run) return;
    if (message.method === 'serverRequest/resolved') {
      for (const [id, pending] of this.pending) if (pending.rpcId === p.requestId && pending.runId === run.id) {
        this.pending.delete(id);
        this.store.db.prepare("UPDATE approvals SET state='resolved' WHERE id=?").run(id);
        this.store.event('approval.resolved', { id, runId: run.id });
      }
    }
    if (run.turnId && (p.turnId ?? p.turn?.id) && (p.turnId ?? p.turn?.id) !== run.turnId) return;
    if (message.method === 'turn/started') this.store.updateRun(run.id, { state: 'running', turnId: p.turn.id });
    if (message.method === 'turn/completed') {
      this.store.updateRun(run.id, { state: p.turn.status === 'completed' ? 'completed' : p.turn.status === 'interrupted' ? 'interrupted' : 'failed',
        turnId: p.turn.id, error: p.turn.error?.message ?? null });
      this.expireApprovals(run.id);
    }
    this.store.event('agent.event', { runId: run.id, ...message });
  }

  private approval(message: RpcMessage) {
    const run = this.store.active(message.params?.threadId);
    if (!run || (run.turnId && message.params?.turnId !== run.turnId) || !approvalMethods.has(message.method!)) {
      this.codex.reject(message.id!, '此客户端不支持此请求'); return;
    }
    const id = randomUUID();
    this.store.db.prepare('INSERT INTO approvals VALUES (?,?,?,?,?)').run(id, run.id, message.method!, JSON.stringify(message.params), 'pending');
    this.pending.set(id, { rpcId: message.id!, runId: run.id, method: message.method! });
    this.store.updateRun(run.id, { state: message.method === 'item/tool/requestUserInput' ? 'waiting_input' : 'waiting_approval' });
    this.store.event('approval.requested', { id, runId: run.id, method: message.method, params: message.params });
  }

  async start(requestId: string, threadId: string, text: string, attachmentIds: string[] = [], mode = 'start') {
    if (!['start', 'steer', 'queue'].includes(mode)) throw new Error('后续消息模式无效');
    const existing = this.store.runByRequest(requestId);
    if (existing) {
      if (existing.threadId !== threadId || existing.text !== text || (this.attachments && JSON.stringify(attachmentIds) !== String(this.store.db.prepare('SELECT ids FROM run_attachments WHERE runId=?').get(existing.id)?.ids ?? '[]'))) throw new Error('请求 ID 已用于另一条消息');
      return existing;
    }
    if (!this.store.managed(threadId)) throw new Error('请先显式恢复此历史会话');
    const active = this.store.active(threadId);
    if (active && (mode === 'start' || active.state === 'unknown')) throw new Error('此会话已有活动或结果待核实的执行');
    const waiting = this.store.runs().some(r => r.threadId === threadId && r.mode === 'queue' && ['sending', 'waiting_queue', 'unknown'].includes(r.state));
    const delivery = active || waiting ? mode : 'start';
    if (delivery === 'steer' && !active?.turnId) throw new Error('当前轮次尚未就绪，请稍后发送引导');
    const inputs = this.attachments?.inputs(threadId, attachmentIds) ?? [];
    const run = this.store.transaction(() => {
      const run = this.store.createRun(requestId, threadId, text, delivery);
      if (this.attachments) this.store.db.prepare('INSERT INTO run_attachments VALUES (?,?)').run(run.id, JSON.stringify(attachmentIds));
      return run;
    });
    if (delivery === 'steer') this.store.updateRun(run.id, { turnId: active!.turnId });
    this.store.event('run.updated', this.store.run(run.id));
    // Persist ownership before crossing the process boundary. An uncertain result is never replayed.
    let dispatched = false;
    try {
      if (!active) await this.ensureLoaded(threadId);
      dispatched = true;
      const input = [{ type: 'text', text }, ...inputs];
      if (delivery === 'steer') {
        const result = await this.codex.request('turn/steer', { threadId, expectedTurnId: active!.turnId, clientUserMessageId: requestId, input });
        this.store.updateRun(run.id, { turnId: result.turnId, state: 'steered', error: null });
      } else if (delivery === 'queue') {
        const result = await this.codex.request('thread/queue/add', { threadId, clientUserMessageId: requestId, input });
        const current = this.store.run(run.id)!;
        this.store.updateRun(run.id, { queueId: result.queuedSubmission.id,
          ...(['sending', 'unknown'].includes(current.state) ? { state: 'waiting_queue', error: null } : {}) });
      } else {
        const result = await this.codex.request('turn/start', { threadId, clientUserMessageId: requestId, input, ...turnSettings(this.store.settings(threadId)) });
        const current = this.store.run(run.id)!;
        if (current.state === 'queued') this.store.updateRun(run.id, { turnId: result.turn.id, state: 'running' });
      }
    } catch (e) {
      if (delivery === 'start' || this.store.run(run.id)?.state === 'sending') {
        this.store.updateRun(run.id, { state: dispatched && !(e instanceof RpcError) ? 'unknown' : 'failed', error: (e as Error).message });
      }
    }
    return this.store.run(run.id)!;
  }

  private async ensureLoaded(threadId: string) {
    const loaded = await this.codex.request('thread/loaded/list', {});
    if (loaded.data.includes(threadId)) return;
    const current = await this.codex.request('thread/read', { threadId, includeTurns: false });
    if (current.thread.status?.type === 'active') throw new Error('会话正由另一端执行');
    const resumed = await this.codex.request('thread/resume', { threadId, excludeTurns: true, ...threadSettings(this.store.settings(threadId)) });
    this.store.saveSettings(threadId, await effectiveSettings(this.codex, resumed));
  }

  async resumeQueue(threadId: string) {
    if (!this.store.managed(threadId) || this.store.active(threadId)) throw new Error('请先结束或核实当前轮次');
    await this.ensureLoaded(threadId);
    const current = await this.codex.request('thread/read', { threadId, includeTurns: false });
    if (current.thread.status?.type !== 'active') await this.codex.request('thread/queue/start', { threadId });
    return { ok: true };
  }

  async reconcile(id: string) {
    const run = this.store.run(id);
    if (!run || run.state !== 'unknown') throw new Error('执行无需核实');
    if (run.mode === 'queue') {
      let cursor: string | null = null;
      do {
        const page = await this.codex.request('thread/queue/list', { threadId: run.threadId, cursor });
        const queued = page.data.find((q: any) => q.clientUserMessageId === run.requestId);
        if (queued) {
          this.store.updateRun(id, { state: 'waiting_queue', queueId: queued.id, turnId: null, error: null });
          return this.store.run(id);
        }
        cursor = page.nextCursor ?? null;
      } while (cursor);
    }
    let cursor: string | null = null;
    do {
      const page = await this.codex.request('thread/turns/list', { threadId: run.threadId, cursor, limit: 20, sortDirection: 'desc', itemsView: 'full' });
      const turn = page.data.find((t: any) => (run.mode === 'start' && t.id === run.turnId)
        || t.items.some((i: any) => i.type === 'userMessage' && i.clientId === run.requestId));
      if (turn) {
        this.store.updateRun(id, { turnId: turn.id, state: run.mode === 'steer' ? 'steered' : turn.status === 'inProgress' ? 'running' : turn.status, error: null });
        break;
      }
      cursor = page.nextCursor ?? null;
    } while (cursor);
    return this.store.run(id);
  }

  async cancel(id: string) {
    const run = this.store.run(id);
    if (run?.mode === 'queue' && run.state === 'waiting_queue' && run.queueId) {
      await this.codex.request('thread/queue/delete', { threadId: run.threadId, queuedSubmissionId: run.queueId });
      this.store.updateRun(id, { state: 'interrupted', error: null });
      return this.store.run(id);
    }
    if (!run?.turnId || !['running', 'waiting_approval', 'waiting_input'].includes(run.state)) throw new Error('执行不可取消');
    await this.codex.request('turn/interrupt', { threadId: run.threadId, turnId: run.turnId });
    return this.store.run(id);
  }

  respond(id: string, decision: string, answers?: Record<string, { answers: string[] }>) {
    const pending = this.pending.get(id);
    const approval = this.store.db.prepare("SELECT * FROM approvals WHERE id=? AND state='pending'").get(id);
    if (!pending || !approval) throw new Error('请求已解决或已过期');
    const run = this.store.run(pending.runId)!;
    if (!['waiting_approval', 'waiting_input'].includes(run.state)) throw new Error('执行已结束');
    const result = pending.method === 'item/tool/requestUserInput' ? { answers: answers ?? {} } : { decision };
    if (pending.method !== 'item/tool/requestUserInput' && !['accept', 'decline', 'cancel'].includes(decision)) throw new Error('无效审批决定');
    this.codex.reply(pending.rpcId, result);
    this.pending.delete(id);
    this.store.db.prepare("UPDATE approvals SET state='resolved' WHERE id=?").run(id);
    this.store.updateRun(run.id, { state: 'running' });
    this.store.event('approval.resolved', { id, runId: run.id });
  }

  private expireApprovals(runId?: string) {
    for (const [id, pending] of this.pending) if (!runId || pending.runId === runId) {
      this.pending.delete(id);
      this.store.db.prepare("UPDATE approvals SET state='expired' WHERE id=?").run(id);
      this.store.event('approval.resolved', { id, runId: pending.runId });
    }
  }
}
