#!/usr/bin/env node
import { cpSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

if (process.platform !== 'linux') throw new Error('此安装器用于具有 systemd 用户服务的 Linux。');
if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('服务需要 Node.js 24+。');
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const version = '0.1.0';
const destination = join(homedir(), '.local/share/agentdeck', version);
const data = join(homedir(), '.agentdeck');
const unit = join(homedir(), '.config/systemd/user/agentdeck.service');
const path = `${dirname(process.execPath)}:${process.env.PATH ?? '/usr/local/bin:/usr/bin:/bin'}`;
const codex = process.env.AGENTDECK_CODEX ?? path.split(':').map(p => join(p, 'codex')).find(existsSync);
if (!codex || !existsSync(codex)) throw new Error('请先安装并配置 Codex CLI，或设置 AGENTDECK_CODEX 为绝对路径。');
// systemd performs specifier expansion even inside quotes.
const quote = value => '"' + value.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('%', '%%') + '"';
const environment = { PATH: path, AGENTDECK_CODEX: resolve(codex), AGENTDECK_DATA_DIR: data };
const python = join(data, 'whisper-venv/bin/python'), model = join(data, 'whisper-model');
if (existsSync(python) && existsSync(join(model, 'model.bin'))) {
  environment.AGENTDECK_TRANSCRIBE_PYTHON = python;
  environment.AGENTDECK_WHISPER_MODEL = model;
}
const service = `[Unit]
Description=AgentDeck host service

[Service]
Type=simple
WorkingDirectory=%h
ExecStart=${quote(process.execPath)} ${quote(join(destination, 'src/main.js'))}
${Object.entries(environment).map(([key, value]) => `Environment=${quote(`${key}=${value}`)}`).join('\n')}
Restart=on-failure
RestartSec=3
UMask=0077

[Install]
WantedBy=default.target
`;
console.log(`AgentDeck ${version}\n安装路径：${destination}\n用户服务：${unit}\n数据与令牌：${data}\nNode：${process.execPath}\nCodex：${codex}`);
if (!process.argv.includes('--install')) {
  console.log('执行 node scripts/install-linux-service.mjs --install 安装。先停止占用 4317 端口的开发服务。');
  process.exit(0);
}
execFileSync('systemctl', ['--user', 'show-environment'], { stdio: 'ignore' });
const options = { cwd: join(repo, 'host-service'), env: { ...process.env, PATH: path }, stdio: 'inherit' };
execFileSync('npm', ['ci', '--cache', join(data, 'npm-cache')], options);
execFileSync('npm', ['run', 'build'], options);
mkdirSync(destination, { recursive: true });
mkdirSync(data, { recursive: true, mode: 0o700 });
mkdirSync(dirname(unit), { recursive: true });
// Stop before replacing a running installation; upgrades should wait for active runs to finish.
writeFileSync(unit, service);
execFileSync('systemctl', ['--user', 'daemon-reload'], { stdio: 'inherit' });
execFileSync('systemctl', ['--user', 'stop', 'agentdeck.service'], { stdio: 'inherit' });
cpSync(join(repo, 'host-service/dist/src'), join(destination, 'src'), { recursive: true });
writeFileSync(join(destination, 'package.json'), JSON.stringify({ type: 'module', version }));
execFileSync('systemctl', ['--user', 'enable', '--now', 'agentdeck.service'], { stdio: 'inherit' });
const linger = execFileSync('loginctl', ['show-user', String(process.getuid()), '-p', 'Linger', '--value'], { encoding: 'utf8' }).trim();
console.log('服务已启动。手机配置 SSH 后会自动获取服务令牌。日志：journalctl --user -u agentdeck.service');
if (linger !== 'yes') console.log('当前 Linger 未启用；最后一个登录会话结束后用户服务可能停止。需要无人登录时运行，请执行 loginctl enable-linger（可能需要管理员授权）。');
