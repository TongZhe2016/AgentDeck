import { Codex, isActiveWriterError } from '../providers/codex.js';
import { Store } from '../storage/store.js';
import { effectiveSettings, threadSettings } from '../providers/execution-settings.js';

export class SessionControl {
  constructor(private codex: Codex, private store: Store) {}

  async takeOver(id: string) {
    if (this.store.active(id)) throw new Error('请先结束或核实本轮执行');
    try {
      const result = await this.codex.request('thread/resume', { threadId: id, excludeTurns: true, ...threadSettings(this.store.settings(id)) });
      return { acquired: true, thread: await this.adopt(result) };
    } catch (error) {
      if (isActiveWriterError(error)) return { acquired: false, writerState: 'external', message: '有其他用户正在使用' };
      throw error;
    }
  }

  async fork(id: string) {
    const result = await this.codex.request('thread/fork', { threadId: id, excludeTurns: true, ...threadSettings(this.store.settings(id)) });
    return this.adopt(result);
  }

  private async adopt(result: any) {
    const settings = await effectiveSettings(this.codex, result);
    this.store.markManaged(result.thread.id);
    this.store.saveSettings(result.thread.id, settings);
    return { ...result.thread, executionSettings: settings, managed: true, writerState: 'owned' };
  }
}
