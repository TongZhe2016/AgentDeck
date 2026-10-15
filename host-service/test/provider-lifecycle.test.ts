import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { join } from 'node:path';
import { tmpdir } from 'node:os';
import { Codex } from '../src/providers/codex.js';

test('an immediately exiting Codex rejects initialization without an unhandled stdin error', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'agentdeck-exiting-codex-'));
  const executable = join(dir, 'codex');
  writeFileSync(executable, '#!/bin/sh\nexit 1\n', { mode: 0o755 });
  const codex = new Codex(executable, dir);
  let stops = 0; codex.on('stopped', () => stops++);
  try {
    await assert.rejects(codex.start());
    // A write after exit must be an ordinary provider error, never a process-level EPIPE.
    await assert.rejects(codex.request('model/list', {}), /不可用/);
    assert.equal(stops, 1);
    await assert.rejects(codex.start());
    assert.equal(stops, 2);
  } finally { codex.close(); rmSync(dir, { recursive: true, force: true }); }
});
