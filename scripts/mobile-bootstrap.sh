#!/bin/sh
set -eu
# Runs from the package uploaded by the phone. Everything stays in this SSH user's home.
export PATH="$HOME/.local/share/agentdeck/node/bin:$HOME/.local/share/agentdeck/tools/node_modules/.bin:/opt/homebrew/bin:/usr/local/bin:$PATH"
root="$HOME/.local/share/agentdeck"
case "$(uname -s)" in Darwin) platform=darwin;; Linux) platform=linux;; *) echo '仅支持 macOS 和 Ubuntu/systemd Linux'; exit 1;; esac
case "$(uname -m)" in arm64|aarch64) arch=arm64;; x86_64) arch=x64;; *) echo '仅支持 x64 和 ARM64'; exit 1;; esac
if ! command -v node >/dev/null 2>&1 || ! node -e 'process.exit(Number(process.versions.node.split(".")[0]) >= 24 ? 0 : 1)'; then
  command -v curl >/dev/null 2>&1 || { echo '请先安装 curl'; exit 1; }
  echo '正在下载 Node.js 24 到用户目录…'
  mkdir -p "$root/node"
  curl -fL --connect-timeout 20 https://nodejs.org/dist/index.tab -o node-index.tab
  version=$(awk '$1 ~ /^v24\./ { print $1; exit }' node-index.tab)
  test -n "$version"
  curl -fL --connect-timeout 20 "https://nodejs.org/dist/$version/node-$version-$platform-$arch.tar.gz" -o node.tgz
  tar -xzf node.tgz --strip-components=1 -C "$root/node"
fi
if ! command -v codex >/dev/null 2>&1; then
  echo '正在安装 Codex CLI 到用户目录…'
  npm install --prefix "$root/tools" --no-audit --no-fund @openai/codex
fi
exec node install.mjs "$@"
