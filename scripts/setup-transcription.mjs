#!/usr/bin/env node
import { execFileSync } from 'node:child_process';
import { homedir } from 'node:os';
import { join } from 'node:path';

const root = process.env.AGENTDECK_DATA_DIR ?? join(homedir(), '.agentdeck');
const environment = join(root, 'whisper-venv');
const model = join(root, 'whisper-model');
const python = join(environment, process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python');
console.log(`安装 faster-whisper 1.2.1 与多语言 base 模型（约 145 MB 权重）\nPython：${python}\n模型：${model}\n录音仅在电脑本地转写。`);
if (!process.argv.includes('--install')) {
  console.log('运行 node scripts/setup-transcription.mjs --install 下载依赖和模型。'); process.exit(0);
}
execFileSync(process.env.AGENTDECK_PYTHON ?? 'python3', ['-m', 'venv', environment], { stdio: 'inherit' });
execFileSync(python, ['-m', 'pip', 'install', 'faster-whisper==1.2.1'], { stdio: 'inherit' });
execFileSync(python, ['-c', 'import sys; from huggingface_hub import snapshot_download; snapshot_download("Systran/faster-whisper-base", local_dir=sys.argv[1], allow_patterns=["model.bin", "config.json", "vocabulary.*", "tokenizer.json", "preprocessor_config.json"])', model], { stdio: 'inherit' });
console.log('安装完成。macOS 用户服务请重新运行 install-macos-service.mjs --install，它会读取上述路径。前台开发请设置 AGENTDECK_TRANSCRIBE_PYTHON 和 AGENTDECK_WHISPER_MODEL。');
