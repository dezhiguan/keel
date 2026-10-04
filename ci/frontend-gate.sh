#!/usr/bin/env bash
# 前端发布门禁。只看源码和清单，不连集群。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

if grep -RInE "from ['\"]openai|from ['\"]dashscope|from ['\"]anthropic|from ['\"]@anthropic" \
  console/src --exclude-dir=mocks; then
  echo "控制台不能引用厂商 SDK" >&2
  exit 1
fi

grep -q 'import.meta.env.DEV' console/src/main.ts

manifest=deploy/k3s/console.yaml
grep -q 'name: keel-console' "$manifest"
grep -q 'nodePort: 31110' "$manifest"
if grep -Eq 'name: keel-llm|name: keel-server|PLACEHOLDER_LLM|PLACEHOLDER_SERVER' "$manifest"; then
  echo "控制台清单不能带后端工作负载" >&2
  exit 1
fi
echo "前端门禁通过"
