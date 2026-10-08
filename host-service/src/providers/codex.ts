import { spawn, type ChildProcessWithoutNullStreams } from 'node:child_process';
import { createInterface } from 'node:readline';
import { EventEmitter } from 'node:events';
import { existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';

export type RpcMessage = { id?: string | number; method?: string; params?: any; result?: any; error?: { message: string; code?: number } };

export class RpcError extends Error {}
export function isActiveWriterError(error: unknown): boolean {
  return /already has (?:an active|a live local) writer/i.test(error instanceof Error ? error.message : String(error));
}

export class Codex extends EventEmitter {
  private process?: ChildProcessWithoutNullStreams;
  private nextId = 0;
  private pending = new Map<number, { resolve: (value: any) => void; reject: (error: Error) => void; timer: NodeJS.Timeout }>();
  private ready?: Promise<void>;
  constructor(private executable = 'codex', private codexHome = process.env.CODEX_HOME ?? join(homedir(), '.codex')) { super(); }

  async writerState(threadId: string): Promise<'owned' | 'external' | 'available'> {
    let cursor: string | null = null;
    do {
      const loaded = await this.request('thread/loaded/list', { cursor, limit: 100 });
      if (loaded.data.includes(threadId)) return 'owned';
      cursor = loaded.nextCursor ?? null;
    } while (cursor);
    // Codex 0.161 holds these files for idle writers too; thread/read reports notLoaded for them.
    // Presence is a hint. Only native resume decides whether the lock is still held or reclaimable.
    return existsSync(join(this.codexHome, 'thread-writer-locks', `${encodeURIComponent(threadId)}.lock`)) ? 'external' : 'available';
  }

  start(): Promise<void> {
    return this.ready ??= this.initialize();
  }

  private async initialize() {
    const child = this.process = spawn(this.executable, ['app-server'], { stdio: 'pipe', env: { ...process.env, CODEX_HOME: this.codexHome } });
    child.stderr.on('data', () => {}); // Drain diagnostics; do not include local secrets in the API.
    const fail = (error: Error) => {
      for (const item of this.pending.values()) { clearTimeout(item.timer); item.reject(error); }
      this.pending.clear();
      this.process = undefined;
      this.ready = undefined;
      this.emit('stopped', error.message);
    };
    child.on('error', fail);
    child.on('exit', (code, signal) => fail(new Error(`Codex 已退出 (${code ?? signal})`)));
    createInterface({ input: child.stdout }).on('line', line => {
      let message: RpcMessage;
      try { message = JSON.parse(line); } catch { return; }
      if (message.method) {
        this.emit(message.id == null ? 'notification' : 'request', message);
      } else if (typeof message.id === 'number') {
        const pending = this.pending.get(message.id);
        if (!pending) return;
        this.pending.delete(message.id); clearTimeout(pending.timer);
        if (message.error) pending.reject(new RpcError(message.error.message));
        else pending.resolve(message.result);
      }
    });
    await this.request('initialize', { clientInfo: { name: 'agentdeck', title: 'AgentDeck', version: '0.1.0' }, capabilities: { experimentalApi: true } });
    this.send({ method: 'initialized', params: {} });
  }

  private send(message: RpcMessage) {
    if (!this.process || this.process.killed) throw new Error('Codex 服务不可用，请重启电脑端服务');
    this.process.stdin.write(JSON.stringify(message) + '\n');
  }

  request(method: string, params: unknown): Promise<any> {
    const id = ++this.nextId;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { this.pending.delete(id); reject(new Error(`${method} 响应超时，结果待核实`)); }, 30_000);
      this.pending.set(id, { resolve, reject, timer });
      try { this.send({ id, method, params }); }
      catch (e) { clearTimeout(timer); this.pending.delete(id); reject(e); }
    });
  }

  reply(id: string | number, result: unknown) { this.send({ id, result }); }
  reject(id: string | number, message: string) { this.send({ id, error: { code: -32601, message } }); }
  close() { this.process?.kill(); }
}
