import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { fileURLToPath } from 'node:url';
import { Attachments } from './attachments.js';

const execute = promisify(execFile);
export class Transcription {
  private busy = false;
  constructor(private attachments: Attachments) {}
  async transcribe(id: string, threadId: string) {
    const attachment = this.attachments.audio(id, threadId);
    if (attachment.text !== undefined) return { text: attachment.text };
    const python = process.env.AGENTDECK_TRANSCRIBE_PYTHON;
    const model = process.env.AGENTDECK_WHISPER_MODEL;
    if (!python || !model) throw new Error('电脑尚未配置语音转写，请运行语音安装脚本并重启服务');
    if (this.busy) throw new Error('电脑正在转写另一段录音，请稍后重试');
    this.busy = true;
    try {
      const { stdout } = await execute(python, [fileURLToPath(new URL('./transcribe.py', import.meta.url)), model, attachment.path],
        { timeout: 120_000, maxBuffer: 512 * 1024 });
      const result = JSON.parse(stdout);
      if (typeof result.text !== 'string') throw new Error('转写结果格式无效');
      this.attachments.saveTranscript(id, result.text);
      return { text: result.text };
    } catch (e) {
      const error = e as Error & { killed?: boolean; stderr?: string };
      throw new Error(error.killed ? '转写超时，录音已保留，可重试或删除' : `语音转写失败：${error.stderr?.trim().slice(-600) || error.message}`);
    } finally { this.busy = false; }
  }
}
