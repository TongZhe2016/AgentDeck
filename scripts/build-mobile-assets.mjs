import { execFileSync } from 'node:child_process';
import { cpSync, mkdirSync, rmSync, writeFileSync } from 'node:fs';
import { resolve, join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const out = resolve(process.argv[2] ?? join(root, 'app/build/generated/mobileAssets'));
for (const project of ['tools/mobile-assets', 'host-service']) {
  execFileSync('npm', ['ci', '--cache', join(root, 'app/build/npm-cache'), '--no-audit', '--no-fund'], { cwd: join(root, project), stdio: 'inherit' });
}
execFileSync('npm', ['run', 'build'], { cwd: join(root, 'host-service'), stdio: 'inherit' });
rmSync(out, { recursive: true, force: true });
mkdirSync(join(out, 'terminal'), { recursive: true });
for (const [source, target] of [
  ['@xterm/xterm/lib/xterm.js', 'xterm.js'], ['@xterm/xterm/css/xterm.css', 'xterm.css'],
  ['@xterm/addon-fit/lib/addon-fit.js', 'addon-fit.js'], ['@xterm/xterm/LICENSE', 'xterm-LICENSE'],
  ['@xterm/addon-fit/LICENSE', 'addon-fit-LICENSE'],
]) cpSync(join(root, 'tools/mobile-assets/node_modules', source), join(out, 'terminal', target));
const stage = join(root, 'app/build/mobile-package');
rmSync(stage, { recursive: true, force: true }); mkdirSync(stage, { recursive: true });
cpSync(join(root, 'host-service/dist/src'), join(stage, 'src'), { recursive: true });
cpSync(join(root, 'scripts/mobile-install.mjs'), join(stage, 'install.mjs'));
cpSync(join(root, 'scripts/mobile-bootstrap.sh'), join(stage, 'bootstrap.sh'));
writeFileSync(join(stage, 'package.json'), JSON.stringify({ type: 'module', version: '0.1.0' }));
execFileSync('tar', ['--no-xattrs', '-czf', join(out, 'agentdeck-host.tgz'), '-C', stage, '.'], { env: { ...process.env, COPYFILE_DISABLE: '1' } });
