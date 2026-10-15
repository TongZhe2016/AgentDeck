import { cpSync, existsSync, mkdirSync, writeFileSync, readFileSync, realpathSync } from 'node:fs';
import { createServer } from 'node:net';
import { homedir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';
import { setTimeout } from 'node:timers/promises';

export function serviceConfig({ platform, home, node, codex, path, data, port, destination }) {
  const env = { PATH: path, AGENTDECK_CODEX: codex, AGENTDECK_DATA_DIR: data, AGENTDECK_PORT: String(port) };
  if (platform === 'linux') {
    const quote = s => '"' + s.replaceAll('\\', '\\\\').replaceAll('"', '\\"').replaceAll('%', '%%') + '"';
    return `[Unit]\nDescription=AgentDeck host service\n[Service]\nType=simple\nWorkingDirectory=%h\nExecStart=${quote(node)} ${quote(join(destination, 'src/main.js'))}\n${Object.entries(env).map(([k,v]) => `Environment=${quote(`${k}=${v}`)}`).join('\n')}\nRestart=on-failure\nRestartSec=3\nUMask=0077\n[Install]\nWantedBy=default.target\n`;
  }
  const xml = s => s.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;');
  return `<?xml version="1.0" encoding="UTF-8"?><!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd"><plist version="1.0"><dict><key>Label</key><string>com.worldcopy.agentdeck</string><key>ProgramArguments</key><array><string>${xml(node)}</string><string>${xml(join(destination, 'src/main.js'))}</string></array><key>WorkingDirectory</key><string>${xml(home)}</string><key>EnvironmentVariables</key><dict>${Object.entries(env).map(([k,v]) => `<key>${k}</key><string>${xml(v)}</string>`).join('')}</dict><key>RunAtLoad</key><true/><key>KeepAlive</key><true/><key>StandardOutPath</key><string>${xml(join(data, 'service.log'))}</string><key>StandardErrorPath</key><string>${xml(join(data, 'service-error.log'))}</string></dict></plist>`;
}

export async function install() {
  const home = homedir(), platform = process.platform;
  if (!['linux', 'darwin'].includes(platform)) throw new Error('仅支持 macOS 和 Ubuntu/systemd Linux');
  if (Number(process.versions.node.split('.')[0]) < 24) throw new Error('服务需要 Node.js 24+');
  const port = Number(process.argv[3] ?? 4317);
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('服务端口无效');
  const requested = process.argv[2] ?? '~/.agentdeck';
  const data = requested === '~' ? home : requested.startsWith('~/') ? join(home, requested.slice(2)) : resolve(home, requested);
  const destination = join(home, '.local/share/agentdeck/0.1.0');
  const config = platform === 'linux' ? join(home, '.config/systemd/user/agentdeck.service') : join(home, 'Library/LaunchAgents/com.worldcopy.agentdeck.plist');
  if (existsSync(config) || existsSync(join(destination, 'src/main.js'))) throw new Error('已存在 AgentDeck 安装；请使用原安装器维护，手机安装仅用于新电脑。');
  const path = process.env.PATH;
  const codex = path.split(':').map(p => join(p, 'codex')).find(existsSync);
  if (!codex) throw new Error('未找到 Codex CLI');
  const run = (cmd,args) => execFileSync(cmd,args,{stdio:'inherit'});
  const domain = `gui/${process.getuid()}`;
  // Check the actual service manager before copying files.
  if (platform === 'linux') run('systemctl', ['--user', 'show-environment']);
  else execFileSync('launchctl', ['print', domain], { stdio: 'ignore' });
  await new Promise((accept, reject) => {
    const probe = createServer(); probe.once('error', reject);
    probe.listen(port, '127.0.0.1', () => probe.close(accept));
  });
  mkdirSync(data, { recursive:true, mode:0o700 }); mkdirSync(dirname(config), { recursive:true });
  cpSync(join(dirname(fileURLToPath(import.meta.url)), 'src'), join(destination, 'src'), { recursive:true });
  writeFileSync(join(destination, 'package.json'), '{"type":"module","version":"0.1.0"}');
  writeFileSync(config, serviceConfig({platform,home,node:process.execPath,codex,path,data,port,destination}));
  if (platform === 'linux') {
    run('systemctl', ['--user','daemon-reload']); run('systemctl',['--user','enable','--now','agentdeck.service']);
    const linger = execFileSync('loginctl',['show-user',String(process.getuid()),'-p','Linger','--value'],{encoding:'utf8'}).trim();
    if (linger !== 'yes') console.log('提示：未启用 Linger。无人登录时持续运行需要 loginctl enable-linger（可能需要管理员授权）。');
  } else run('launchctl', ['bootstrap',domain,config]);
  let healthy = false;
  for (let i=0; i<30; i++) {
    try {
      const token = readFileSync(join(data, 'token'), 'utf8').trim();
      const res = await fetch(`http://127.0.0.1:${port}/v1/health`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(2000) });
      const json = await res.json(); if (res.ok && json.protocol === 1) { healthy=true; break; }
    } catch {}
    await setTimeout(500);
  }
  if (!healthy) throw new Error('服务已安装，但健康检查未通过；请查看用户服务日志。');
  console.log(`安装完成：127.0.0.1:${port}\n服务目录：${data}\n新安装的命令位于用户目录，在终端先执行：\nexport PATH="$HOME/.local/share/agentdeck/node/bin:$HOME/.local/share/agentdeck/tools/node_modules/.bin:$PATH"\n然后运行 codex login status；如未登录，运行 codex login --device-auth，或按电脑现有模型提供商配置完成认证。`);
}
if (process.argv[1] && realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) await install();
