#!/usr/bin/env bash
# 一键验收：单测 +（有 Docker 时）集成测试 + 前端 typecheck/build。
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if [[ ! -f .env ]]; then
  echo "缺少 .env，先: cp .env.example .env"
  exit 1
fi

echo "== unit tests =="
./mvnw -q test

if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  echo "== integration tests =="
  ./mvnw -q -Pintegration verify
else
  echo "== skip integration tests (docker unavailable) =="
fi

echo "== frontend =="
(cd web && npm ci && npm run typecheck && npm run build)

if command -v k6 >/dev/null 2>&1; then
  echo "== k6 scripts present, not started against a live stack =="
  echo "    需要压测时先起服务，再跑 scripts/k6/*.js"
fi

echo "accept.sh finished"
