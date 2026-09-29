import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, writeFile, rm, rename } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { execFileSync } from 'node:child_process';
import { status, diff, graph, commitDetail } from '../src/git/git.js';

async function repository(t: any) {
  const dir = await mkdtemp(join(tmpdir(), 'agentdeck-git-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  const git = (...args: string[]) => execFileSync('git', args, { cwd: dir, encoding: 'utf8', stdio: ['ignore','pipe','pipe'] }).trim();
  git('init', '-b', 'main'); git('config', 'user.name', 'Fixture'); git('config', 'user.email', 'fixture@example.invalid');
  return { dir, git };
}

test('empty, partial staging, rename, literal paths and untracked preview', async t => {
  const { dir, git } = await repository(t);
  assert.equal((await status(dir)).commitCount, 0);
  assert.deepEqual((await graph(dir, 'head')).commits, []);
  const path = '中文 space\nfile.txt';
  await writeFile(join(dir, path), 'one\n'); git('add', '.'); git('commit', '-m', 'root');
  await writeFile(join(dir, path), 'one\ntwo\n'); git('add', '.');
  await writeFile(join(dir, path), 'one\ntwo\nthree\n');
  await writeFile(join(dir, ':(glob)*.txt'), 'literal content');
  const state = await status(dir);
  assert.equal(state.changedFiles, 2);
  assert.equal(state.changes.filter(c => c.path === path).length, 2);
  assert.equal(state.changes.find(c => c.path === path && c.group === 'staged')?.additions, 1);
  assert.equal(state.changes.find(c => c.path === path && c.group === 'unstaged')?.additions, 1);
  assert.match((await diff(dir, path, 'staged')).text, /\+two/);
  assert.doesNotMatch((await diff(dir, path, 'staged')).text, /\+three/);
  assert.match((await diff(dir, path, 'unstaged')).text, /\+three/);
  assert.equal((await diff(dir, ':(glob)*.txt', 'untracked')).text, 'literal content');
  git('add', '.'); git('commit', '-m', 'changes');
  await rename(join(dir, path), join(dir, '-renamed.txt')); git('add', '.');
  const renamed = (await status(dir)).changes[0];
  assert.equal(renamed.oldPath, path); assert.equal(renamed.path, '-renamed.txt');
});

test('graph retains both merge parents and fixed pagination roots', async t => {
  const { dir, git } = await repository(t);
  await writeFile(join(dir, 'root'), 'root'); git('add', '.'); git('commit', '-m', 'root');
  const root = git('rev-parse', 'HEAD');
  git('checkout', '-b', 'feature'); await writeFile(join(dir, 'feature'), 'feature'); git('add', '.'); git('commit', '-m', 'feature');
  const feature = git('rev-parse', 'HEAD');
  git('checkout', 'main'); await writeFile(join(dir, 'main'), 'main'); git('add', '.'); git('commit', '-m', 'main');
  const main = git('rev-parse', 'HEAD'); git('merge', '--no-ff', 'feature', '-m', 'merge');
  const merge = git('rev-parse', 'HEAD');
  const first = await graph(dir, 'head');
  assert.deepEqual(first.commits[0].parents, [main, feature]);
  assert.equal(first.commits.at(-1)!.oid, root);
  assert.equal((await status(dir)).commitCount, 4);
  assert.deepEqual((await commitDetail(dir, merge)).parents, [main, feature]);
  assert.deepEqual((await commitDetail(dir, merge, 0)).paths, ['feature']);
  assert.deepEqual((await commitDetail(dir, merge, 1)).paths, ['main']);
  for (let i = 0; i < 50; i++) git('commit', '--allow-empty', '-m', `extra ${i}`);
  const page = await graph(dir, 'head'); assert.ok(page.nextCursor);
  git('commit', '--allow-empty', '-m', 'new after snapshot');
  const second = await graph(dir, 'head', page.nextCursor!);
  const all = [...page.commits, ...second.commits];
  assert.equal(all.length, 54); assert.equal(new Set(all.map(c => c.oid)).size, 54);
  assert.ok(all.every(c => c.subject !== 'new after snapshot'));
});
