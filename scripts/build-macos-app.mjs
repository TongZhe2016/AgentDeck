#!/usr/bin/env node
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

if (process.platform !== 'darwin') throw new Error('Mac App 需要在 macOS 上构建。');
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const output = join(repo, '.local/macos');
const bundle = join(output, 'AgentDeck Server.app');
const executable = join(bundle, 'Contents/MacOS/AgentDeckServer');
mkdirSync(dirname(executable), { recursive: true });
mkdirSync(join(bundle, 'Contents/Resources'), { recursive: true });
execFileSync('xcrun', ['swiftc', '-parse-as-library', '-swift-version', '5', '-target', `${process.arch === 'arm64' ? 'arm64' : 'x86_64'}-apple-macosx13.0`, '-O', join(repo, 'macos/AgentDeckServer.swift'), '-o', executable], { stdio: 'inherit' });
cpSync(join(repo, 'macos/Info.plist'), join(bundle, 'Contents/Info.plist'));
const iconset = join(output, 'AppIcon.iconset');
rmSync(iconset, { recursive: true, force: true });
execFileSync(executable, ['--write-icon', iconset], { stdio: 'inherit' });
execFileSync('iconutil', ['-c', 'icns', iconset, '-o', join(bundle, 'Contents/Resources/AppIcon.icns')], { stdio: 'inherit' });
execFileSync('codesign', ['--force', '--sign', '-', bundle], { stdio: 'inherit' });
console.log(`Mac App 已构建：${bundle}`);
