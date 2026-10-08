import { open } from 'node:fs/promises';
import { basename, isAbsolute, resolve } from 'node:path';
import { pipeline } from 'node:stream/promises';
import type { ServerResponse } from 'node:http';

// This endpoint uses the authenticated SSH account's filesystem permissions.
export async function downloadFile(path: string, cwd: string | null, response: ServerResponse) {
  if (!isAbsolute(path) && (!cwd || !isAbsolute(cwd))) throw new Error('相对文件路径需要会话的项目目录');
  const filePath = isAbsolute(path) ? path : resolve(cwd!, path);
  const file = await open(filePath, 'r');
  try {
    const info = await file.stat();
    if (!info.isFile()) throw new Error('只能下载文件，请先将文件夹打包');
    response.writeHead(200, {
      'Content-Type': 'application/octet-stream',
      'Content-Length': info.size,
      'Content-Disposition': `attachment; filename*=UTF-8''${encodeURIComponent(basename(filePath))}`,
    });
    await pipeline(file.createReadStream(), response);
  } finally { await file.close(); }
}
