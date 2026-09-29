#!/usr/bin/env node
import { cpSync, mkdirSync, writeFileSync, existsSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

if (process.platform !== 'darwin') throw new Error('此安装器用于 macOS；其他系统可先使用 host-service 的前台入口。');
const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const version = '0.1.0';
const destination = join(homedir(), '.local/share/agentdeck', version);
const data = join(homedir(), '.agentdeck');
const plist = join(homedir(), 'Library/LaunchAgents/com.worldcopy.agentdeck.plist');
const escape = value => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
const codex = process.env.AGENTDECK_CODEX ?? process.env.PATH.split(':').map(path => join(path, 'codex')).find(existsSync);
if (!codex) throw new Error('请先安装并配置 Codex CLI，或设置 AGENTDECK_CODEX。');
const xml = `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>com.worldcopy.agentdeck</string>
<key>ProgramArguments</key><array><string>${escape(process.execPath)}</string><string>${escape(join(destination, 'src/main.js'))}</string></array>
<key>WorkingDirectory</key><string>${escape(homedir())}</string>
<key>EnvironmentVariables</key><dict><key>PATH</key><string>${escape(process.env.PATH)}</string><key>AGENTDECK_CODEX</key><string>${escape(codex)}</string><key>AGENTDECK_DATA_DIR</key><string>${escape(data)}</string></dict>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardOutPath</key><string>${escape(join(data, 'service.log'))}</string>
<key>StandardErrorPath</key><string>${escape(join(data, 'service-error.log'))}</string>
</dict></plist>
`;
console.log(`AgentDeck ${version}\n安装路径：${destination}\n用户服务：${plist}\n数据与令牌：${data}\n启动方式：当前用户的 launchd，登录后运行。`);
if (!process.argv.includes('--install')) {
  console.log('执行 node scripts/install-macos-service.mjs --install 开始安装。安装前请停止占用 4317 端口的开发服务。');
  process.exit(0);
}
execFileSync('npm', ['ci'], { cwd: join(repo, 'host-service'), stdio: 'inherit' });
execFileSync('npm', ['run', 'build'], { cwd: join(repo, 'host-service'), stdio: 'inherit' });
mkdirSync(destination, { recursive: true }); mkdirSync(data, { recursive: true, mode: 0o700 }); mkdirSync(dirname(plist), { recursive: true });
cpSync(join(repo, 'host-service/dist/src'), join(destination, 'src'), { recursive: true });
writeFileSync(join(destination, 'package.json'), JSON.stringify({ type: 'module', version }));
writeFileSync(plist, xml);
execFileSync('plutil', ['-lint', plist], { stdio: 'inherit' });
const domain = `gui/${process.getuid()}`;
try { execFileSync('launchctl', ['bootout', `${domain}/com.worldcopy.agentdeck`], { stdio: 'ignore' }); } catch {}
execFileSync('launchctl', ['bootstrap', domain, plist], { stdio: 'inherit' });
console.log('服务已启动。将 token 文件内容填入手机主机设置。API key 仍由电脑端 Codex 配置管理。');
