import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import type { IncomingMessage } from 'node:http';
import { Store } from '../storage/store.js';

export class Attachments {
  constructor(private store: Store, private directory: string) {
    store.db.exec(`CREATE TABLE IF NOT EXISTS attachments (id TEXT PRIMARY KEY, threadId TEXT NOT NULL, path TEXT NOT NULL, mime TEXT NOT NULL, size INTEGER NOT NULL, createdAt TEXT NOT NULL);
      CREATE TABLE IF NOT EXISTS run_attachments (runId TEXT PRIMARY KEY, ids TEXT NOT NULL);`);
  }
  async upload(id: string, threadId: string, request: IncomingMessage) {
    if (!/^[a-zA-Z0-9-]{1,80}$/.test(id) || !threadId) throw new Error('附件 ID 或会话无效');
    const existing = this.store.db.prepare('SELECT * FROM attachments WHERE id=?').get(id);
    if (existing) {
      request.resume();
      if (existing.threadId !== threadId) throw new Error('附件属于另一会话');
      return { id, mime: existing.mime, size: existing.size };
    }
    const mime = request.headers['content-type']?.split(';')[0] ?? '';
    const extensions: Record<string,string> = { 'image/jpeg': '.jpg', 'image/png': '.png', 'image/webp': '.webp', 'audio/mp4': '.m4a', 'audio/wav': '.wav' };
    if (!extensions[mime]) throw new Error('不支持的附件类型');
    const chunks: Buffer[] = []; let size = 0;
    for await (const chunk of request) {
      size += chunk.length;
      if (size > 10 * 1024 * 1024) throw new Error('单个附件上限 10 MiB');
      chunks.push(chunk);
    }
    const bytes = Buffer.concat(chunks);
    if (size < 12) throw new Error('附件内容不完整');
    const valid = mime === 'image/jpeg' ? bytes[0] === 255 && bytes[1] === 216 :
      mime === 'image/png' ? bytes.subarray(0, 8).equals(Buffer.from([137,80,78,71,13,10,26,10])) :
      mime === 'image/webp' ? bytes.toString('ascii',8,12) === 'WEBP' :
      mime === 'audio/wav' ? bytes.toString('ascii',8,12) === 'WAVE' : bytes.toString('ascii',4,8) === 'ftyp';
    if (!valid) throw new Error('附件内容与格式不一致');
    await mkdir(this.directory, { recursive: true, mode: 0o700 });
    const path = join(this.directory, id + extensions[mime]);
    await writeFile(path + '.upload', bytes, { mode: 0o600 });
    await rename(path + '.upload', path);
    this.store.db.prepare('INSERT INTO attachments VALUES (?,?,?,?,?,?)').run(id, threadId, path, mime, size, new Date().toISOString());
    return { id, mime, size };
  }
  inputs(threadId: string, ids: string[]) {
    if (ids.length > 4) throw new Error('每条消息最多 4 个附件');
    return ids.map(id => {
      const attachment = this.store.db.prepare('SELECT * FROM attachments WHERE id=?').get(id);
      if (!attachment || attachment.threadId !== threadId) throw new Error('附件未上传完成或属于另一会话');
      return { type: String(attachment.mime).startsWith('image/') ? 'localImage' : 'localAudio', path: attachment.path };
    });
  }
  async download(id: string) {
    const attachment = this.store.db.prepare('SELECT * FROM attachments WHERE id=?').get(id);
    if (!attachment) throw new Error('附件不存在');
    return { bytes: await readFile(String(attachment.path)), mime: String(attachment.mime) };
  }
  async remove(id: string) {
    // Retain files referenced by execution history so native resume keeps its inputs.
    const used = this.store.db.prepare('SELECT ids FROM run_attachments').all().some(row => JSON.parse(String(row.ids)).includes(id));
    if (used) throw new Error('此附件已用于执行，保留以供原生历史恢复');
    const row = this.store.db.prepare('SELECT path FROM attachments WHERE id=?').get(id);
    if (row) await rm(String(row.path), { force: true });
    this.store.db.prepare('DELETE FROM attachments WHERE id=?').run(id);
  }
}
