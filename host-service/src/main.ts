import { mkdirSync, existsSync, readFileSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { randomBytes } from 'node:crypto';
import { Codex } from './providers/codex.js';
import { Store } from './storage/store.js';
import { api } from './api/server.js';

const dataDir = process.env.AGENTDECK_DATA_DIR ?? join(homedir(), '.agentdeck');
mkdirSync(dataDir, { recursive: true, mode: 0o700 });
const tokenPath = join(dataDir, 'token');
if (!existsSync(tokenPath)) writeFileSync(tokenPath, randomBytes(32).toString('base64url'), { mode: 0o600 });
const token = readFileSync(tokenPath, 'utf8').trim();
if (!token) throw new Error('服务令牌为空');
const codex = new Codex(process.env.AGENTDECK_CODEX ?? 'codex');
const store = new Store(join(dataDir, 'agentdeck.sqlite'));
const server = api(token, codex, store);
const port = Number(process.env.AGENTDECK_PORT ?? 4317);
server.listen(port, '127.0.0.1', () => console.log(`AgentDeck 0.1.0 listening on 127.0.0.1:${port}; token file: ${tokenPath}`));
server.on('error', e => { console.error(e.message); codex.close(); store.close(); process.exitCode = 1; });
for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => {
  codex.close(); server.close(); server.closeAllConnections();
  setTimeout(() => process.exit(0), 500).unref();
});
