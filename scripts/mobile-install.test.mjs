import { test } from 'node:test';
import assert from 'node:assert/strict';
import { serviceConfig } from './mobile-install.mjs';
import { execFileSync, spawn } from 'node:child_process';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { once } from 'node:events';

const options = { home: '/home/test space', node: '/opt/node/bin/node', codex: '/opt/codex', path: '/opt/node/bin:/usr/bin', data: '/home/test space/data & more', port: 5432, destination: '/home/test space/.local/share/agentdeck/0.1.0' };
test('systemd configuration preserves spaces, literal percent, custom data and port', () => {
  const config = serviceConfig({ ...options, platform:'linux', data:'/tmp/100% work' });
  assert.match(config, /Environment="AGENTDECK_DATA_DIR=\/tmp\/100%% work"/);
  assert.match(config, /AGENTDECK_PORT=5432/);
  assert.match(config, /WorkingDirectory=%h/);
  assert.match(config, /ExecStart="\/opt\/node\/bin\/node" "\/home\/test space\//);
});
test('launchd configuration escapes XML paths and records runtime and port', () => {
  const config = serviceConfig({ ...options, platform:'darwin' });
  assert.match(config, /data &amp; more/); assert.match(config, /<string>5432<\/string>/);
  assert.match(config, /<string>\/opt\/node\/bin\/node<\/string>/);
});
test('uploaded package installs into an isolated home and reaches authenticated service health', async t => {
  // Service manager doubles start the actual bundled HTTP service in a disposable home.
  const root = mkdtempSync(join(tmpdir(), 'agentdeck-mobile-'));
  const stage = join(root,'upload'), bin = join(root,'bin'); mkdirSync(stage); mkdirSync(bin);
  const port = 24573;
  t.after(() => rmSync(root,{recursive:true,force:true}));
  execFileSync('tar',['-xzf',resolve('app/build/generated/mobileAssets/agentdeck-host.tgz'),'-C',stage]);
  writeFileSync(join(bin,'codex'), '#!/bin/sh\nexit 1\n',{mode:0o755});
  const data = join(root,"custom data's & folder");
  const main = join(root,'.local/share/agentdeck/0.1.0/src/main.js');
  const manager = `#!/bin/sh\ncase "$1 $2" in\n "bootstrap "*|"--user enable") "${process.execPath}" "${main}" >"${root}/service.log" 2>&1 & echo $! > "${root}/pid";;\nesac\nexit 0\n`;
  writeFileSync(join(bin,'launchctl'),manager,{mode:0o755}); writeFileSync(join(bin,'systemctl'),manager,{mode:0o755});
  writeFileSync(join(bin,'loginctl'),'#!/bin/sh\necho yes\n',{mode:0o755});
  const env = { ...process.env, HOME:root, PATH:`${bin}:${process.env.PATH}`, AGENTDECK_DATA_DIR:data, AGENTDECK_PORT:String(port) };
  // The manager double forwards the same environment the generated real service config contains.
  try {
    const child = spawn(process.execPath,[join(stage,'install.mjs'),data,String(port)],{env});
    let output = ''; child.stdout.on('data',x=>output+=x); child.stderr.on('data',x=>output+=x);
    const [code] = await once(child,'exit'); assert.equal(code,0,output);
    assert.match(output,/安装完成/);
    const token = readFileSync(join(data,'token'),'utf8').trim();
    const res = await fetch(`http://127.0.0.1:${port}/v1/health`,{headers:{Authorization:`Bearer ${token}`}});
    assert.equal((await res.json()).protocol,1); assert.ok(existsSync(main));
    const retry = spawn(process.execPath,[join(stage,'install.mjs'),data,String(port)],{env});
    let err='';retry.stderr.on('data',x=>err+=x);
    const [retryCode] = await once(retry,'exit'); assert.notEqual(retryCode,0); assert.match(err,/已存在 AgentDeck 安装/);
  } finally { if (existsSync(join(root,'pid'))) process.kill(Number(readFileSync(join(root,'pid'),'utf8').trim()),'SIGTERM'); }
});
