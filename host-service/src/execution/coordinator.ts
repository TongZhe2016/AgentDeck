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
      for (const run of store.runs()) if (['queued', 'running', 'waiting_approval', 'waiting_input'].includes(run.state)) {
        store.updateRun(run.id, { state: 'unknown', error: reason });
      }
      this.expireApprovals();
    });
  }

  private notification(message: RpcMessage) {
    const p = message.params ?? {};
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

  async start(requestId: string, threadId: string, text: string, attachmentIds: string[] = []) {
    const existing = this.store.runByRequest(requestId);
    if (existing) {
      if (existing.threadId !== threadId || existing.text !== text || (this.attachments && JSON.stringify(attachmentIds) !== String(this.store.db.prepare('SELECT ids FROM run_attachments WHERE runId=?').get(existing.id)?.ids ?? '[]'))) throw new Error('请求 ID 已用于另一条消息');
      return existing;
    }
    if (!this.store.managed(threadId)) throw new Error('请先显式恢复此历史会话');
    if (this.store.active(threadId)) throw new Error('此会话已有活动或结果待核实的执行');
    const inputs = this.attachments?.inputs(threadId, attachmentIds) ?? [];
    const run = this.store.transaction(() => {
      const run = this.store.createRun(requestId, threadId, text);
      if (this.attachments) this.store.db.prepare('INSERT INTO run_attachments VALUES (?,?)').run(run.id, JSON.stringify(attachmentIds));
      return run;
    });
    this.store.event('run.updated', run);
    // Persist ownership before crossing the process boundary. An uncertain result is never replayed.
    let dispatched = false;
    try {
      const loaded = await this.codex.request('thread/loaded/list', {});
      if (!loaded.data.includes(threadId)) {
        const current = await this.codex.request('thread/read', { threadId, includeTurns: false });
        if (current.thread.status?.type === 'active') throw new Error('会话正由另一端执行');
        const resumed = await this.codex.request('thread/resume', { threadId, excludeTurns: true, ...threadSettings(this.store.settings(threadId)) });
        this.store.saveSettings(threadId, await effectiveSettings(this.codex, resumed));
      }
      dispatched = true;
      const result = await this.codex.request('turn/start', { threadId, input: [{ type: 'text', text }, ...inputs], ...turnSettings(this.store.settings(threadId)) });
      const current = this.store.run(run.id)!;
      if (current.state === 'queued') this.store.updateRun(run.id, { turnId: result.turn.id, state: 'running' });
    } catch (e) { this.store.updateRun(run.id, { state: dispatched && !(e instanceof RpcError) ? 'unknown' : 'failed', error: (e as Error).message }); }
    return this.store.run(run.id)!;
  }

  async cancel(id: string) {
    const run = this.store.run(id);
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
