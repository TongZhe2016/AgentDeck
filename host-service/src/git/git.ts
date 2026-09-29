import { execFile, spawn } from 'node:child_process';
import { promisify } from 'node:util';
import { open, realpath, lstat, readlink } from 'node:fs/promises';
import { resolve, relative, isAbsolute } from 'node:path';
const exec = promisify(execFile);
const env = { ...process.env, GIT_OPTIONAL_LOCKS: '0', GIT_LITERAL_PATHSPECS: '1', GIT_PAGER: 'cat' };
export type Change = { path: string; oldPath?: string; group: 'staged' | 'unstaged' | 'untracked' | 'conflict'; status: string; submodule?: string; additions?: number | null; deletions?: number | null; binary?: boolean };

async function git(cwd: string, args: string[]) {
  try {
    const { stdout } = await exec('git', ['-c', 'color.ui=false', ...args], { cwd, env, timeout: 15_000, maxBuffer: 4 * 1024 * 1024 });
    return stdout;
  } catch (e) {
    if ((e as NodeJS.ErrnoException).code === 'ENOENT') throw new Error('无法读取项目：请确认电脑上的目录存在，并且已安装 Git');
    throw e;
  }
}

export function parseStatus(raw: string) {
  const records = raw.split('\0');
  const changes: Change[] = [];
  const headers: Record<string, string> = {};
  for (let i = 0; i < records.length; i++) {
    const record = records[i];
    if (!record) continue;
    if (record.startsWith('# ')) { const split = record.indexOf(' ', 2); headers[record.slice(2, split)] = record.slice(split + 1); continue; }
    if (record.startsWith('? ')) { changes.push({ path: record.slice(2), group: 'untracked', status: '新增' }); continue; }
    if (record.startsWith('! ')) continue;
    const fields = record.split(' ');
    const kind = fields[0];
    const path = fields.slice(kind === '1' ? 8 : kind === '2' ? 9 : 10).join(' ');
    const xy = fields[1];
    if (kind === 'u') { changes.push({ path, group: 'conflict', status: '冲突' }); continue; }
    const oldPath = kind === '2' ? records[++i] : undefined;
    const labels: Record<string, string> = { M: '修改', A: '新增', D: '删除', R: '重命名', C: '复制', T: '类型变化' };
    if (xy[0] !== '.') changes.push({ path, oldPath, group: 'staged', status: labels[xy[0]] ?? xy[0], submodule: fields[2] });
    if (xy[1] !== '.') changes.push({ path, oldPath, group: 'unstaged', status: labels[xy[1]] ?? xy[1], submodule: fields[2] });
  }
  return { headers, changes };
}

export function parseNumstat(raw: string) {
  const fields = raw.split('\0'); const stats = new Map<string, { additions: number | null; deletions: number | null; binary: boolean }>();
  for (let i = 0; i < fields.length; i++) {
    const field = fields[i]; if (!field) continue;
    const first = field.indexOf('\t'), second = field.indexOf('\t', first + 1);
    const added = field.slice(0, first), deleted = field.slice(first + 1, second);
    let path = field.slice(second + 1);
    if (!path) { i++; path = fields[++i]; }
    stats.set(path, { additions: added === '-' ? null : Number(added), deletions: deleted === '-' ? null : Number(deleted), binary: added === '-' });
  }
  return stats;
}

export async function status(cwd: string) {
  const root = (await git(cwd, ['rev-parse', '--show-toplevel'])).trim();
  const { headers, changes } = parseStatus(await git(root, ['status', '--porcelain=v2', '-z', '--branch', '--untracked-files=all']));
  const [stagedStats, unstagedStats] = await Promise.all([
    git(root, ['diff', '--cached', '--numstat', '-z', '--no-ext-diff', '--no-textconv']).then(parseNumstat),
    git(root, ['diff', '--numstat', '-z', '--no-ext-diff', '--no-textconv']).then(parseNumstat),
  ]);
  for (const change of changes) Object.assign(change, (change.group === 'staged' ? stagedStats : unstagedStats).get(change.path) ?? {});
  const unborn = headers['branch.oid'] === '(initial)';
  const counts = headers['branch.ab']?.split(' ');
  return { root, gitDir: (await git(root, ['rev-parse', '--absolute-git-dir'])).trim(),
    commonDir: (await git(root, ['rev-parse', '--path-format=absolute', '--git-common-dir'])).trim(),
    branch: headers['branch.head'], head: unborn ? null : headers['branch.oid'],
    upstream: headers['branch.upstream'] ?? null, ahead: counts ? Number(counts[0].slice(1)) : null,
    behind: counts ? Number(counts[1].slice(1)) : null,
    commitCount: unborn ? 0 : Number(await git(root, ['rev-list', '--count', 'HEAD'])),
    shallow: (await git(root, ['rev-parse', '--is-shallow-repository'])).trim() === 'true',
    changedFiles: new Set(changes.map(c => c.path)).size, changes, syncedAt: new Date().toISOString() };
}

async function boundedGit(cwd: string, args: string[]) {
  return new Promise<{ text: string; truncated: boolean }>((resolveResult, reject) => {
    const child = spawn('git', ['-c', 'color.ui=false', ...args], { cwd, env });
    const chunks: Buffer[] = []; let size = 0; let truncated = false; let stderr = '';
    const limit = 512 * 1024;
    const timer = setTimeout(() => { child.kill(); reject(new Error('Git 查询超时')); }, 15_000);
    child.stdout.on('data', (chunk: Buffer) => {
      const remaining = limit - size;
      if (remaining > 0) { chunks.push(chunk.subarray(0, remaining)); size += Math.min(chunk.length, remaining); }
      if (chunk.length > remaining) { truncated = true; child.kill(); }
    });
    child.stderr.on('data', chunk => { stderr = (stderr + chunk).slice(-4096); });
    child.on('error', e => { clearTimeout(timer); reject(e); });
    child.on('close', code => {
      clearTimeout(timer);
      if (code && !truncated) reject(new Error(stderr || `Git 查询失败 (${code})`));
      else resolveResult({ text: Buffer.concat(chunks).toString('utf8'), truncated });
    });
  });
}

export async function diff(cwd: string, path: string, group: string, commit?: string, parentIndex = 0) {
  const root = (await git(cwd, ['rev-parse', '--show-toplevel'])).trim();
  const rel = relative(root, resolve(root, path));
  if (!path || rel === '..' || rel.startsWith('../') || isAbsolute(rel)) throw new Error('文件不在当前工作树中');
  if (group === 'untracked') {
    if ((await lstat(resolve(root, path))).isSymbolicLink()) return { text: await readlink(resolve(root, path)), preview: true, truncated: false, symlink: true };
    const absolute = await realpath(resolve(root, path));
    const physical = relative(root, absolute);
    if (physical === '..' || physical.startsWith('../')) throw new Error('此链接指向工作树之外');
    const file = await open(absolute, 'r');
    try {
      const buffer = Buffer.alloc(512 * 1024);
      const { bytesRead } = await file.read(buffer);
      const binary = buffer.subarray(0, bytesRead).includes(0);
      return { text: binary ? '二进制文件' : buffer.subarray(0, bytesRead).toString('utf8'), binary,
        truncated: (await file.stat()).size > bytesRead, preview: true };
    } finally { await file.close(); }
  }
  const args = ['diff', '--no-ext-diff', '--no-textconv', '--no-color', '--find-renames'];
  if (commit) {
    if (!/^[0-9a-f]{40,64}$/.test(commit)) throw new Error('无效提交 ID');
    const parents = (await git(root, ['show', '-s', '--format=%P', commit])).trim().split(' ').filter(Boolean);
    if (parents.length) {
      if (!parents[parentIndex]) throw new Error('父提交不存在');
      args.push(parents[parentIndex], commit);
    } else {
      return boundedGit(root, ['show', '--format=', '--root', '--no-ext-diff', '--no-textconv', commit, '--', path]);
    }
  } else if (group === 'staged') args.push('--cached');
  else if (!['unstaged', 'conflict'].includes(group)) throw new Error('无效变更分组');
  args.push('--', path);
  return boundedGit(root, args);
}

type Cursor = { roots: string[]; offset: number; cwd: string; scope: string };
export async function graph(cwd: string, scope: string, cursor?: string) {
  const root = (await git(cwd, ['rev-parse', '--show-toplevel'])).trim();
  let snapshot: Cursor;
  if (cursor) {
    snapshot = JSON.parse(Buffer.from(cursor, 'base64url').toString());
    if (snapshot.cwd !== root || snapshot.scope !== scope || !Number.isInteger(snapshot.offset) || snapshot.offset < 0 ||
        !snapshot.roots.every(oid => /^[0-9a-f]{40,64}$/.test(oid))) throw new Error('Graph 游标无效，请刷新');
  } else {
    const state = await status(root);
    const roots = scope === 'all' ? (await git(root, ['for-each-ref', '--format=%(objectname)'])).trim().split('\n').filter(Boolean) : [];
    if (state.head) roots.push(state.head);
    snapshot = { roots: [...new Set(roots)], offset: 0, cwd: root, scope };
  }
  if (!snapshot.roots.length) return { commits: [], nextCursor: null };
  const raw = await git(root, ['log', '--topo-order', '-z', '--format=%H%x00%P%x00%an%x00%aI%x00%s%x00%D',
    `--skip=${snapshot.offset}`, '-n', '51', ...snapshot.roots, '--']);
  const fields = raw.split('\0'); const commits = [];
  for (let i = 0; i + 5 < fields.length; i += 6) commits.push({ oid: fields[i], parents: fields[i + 1].split(' ').filter(Boolean),
    author: fields[i + 2], date: fields[i + 3], subject: fields[i + 4], refs: fields[i + 5] });
  const more = commits.length > 50;
  return { commits: commits.slice(0, 50), nextCursor: more ? Buffer.from(JSON.stringify({ ...snapshot, offset: snapshot.offset + 50 })).toString('base64url') : null };
}

export async function commitDetail(cwd: string, oid: string, parentIndex = 0) {
  if (!/^[0-9a-f]{40,64}$/.test(oid)) throw new Error('无效提交 ID');
  const message = await git(cwd, ['show', '-s', '--format=%B', oid]);
  const parents = (await git(cwd, ['show', '-s', '--format=%P', oid])).trim().split(' ').filter(Boolean);
  if (parents.length && !parents[parentIndex]) throw new Error('父提交不存在');
  const metadata = (await git(cwd, ['show', '-s', '--format=%an%x00%aI', oid])).trim().split('\0');
  const paths = (await git(cwd, ['diff-tree', '--root', '--no-commit-id', '--name-only', '-r', '-z',
    ...(parents.length ? [parents[parentIndex], oid] : [oid])])).split('\0').filter(Boolean);
  return { oid, message, parents, paths, parentIndex, author: metadata[0], date: metadata[1] };
}
