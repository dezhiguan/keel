#!/usr/bin/env bash
# 后端发布门禁。只看源码和清单，不连集群。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"

python3 - <<'PY'
import re
import sys
from pathlib import Path

text = Path("keel-llm/src/main/resources/application.yaml").read_text()
for name in ("qwen-plus", "deepseek-v3"):
    if name not in text:
        sys.exit(f"薄网关配置缺少模型 {name}")
prices = re.findall(r'(?:input|output)-cny-per-token:\s*"?([0-9.]+)"?', text)
if len(prices) < 4:
    sys.exit("单价没配齐：qwen-plus 和 deepseek-v3 都要有输入、输出单价")
for value in prices:
    if float(value) <= 0:
        sys.exit("单价写成了 0，进程会拒绝启动")
print("单价门禁通过")
PY

if grep -RInE 'sk-[A-Za-z0-9]{10,}|AKIA[0-9A-Z]{16}' \
  keel-llm/src keel-server/src deploy/k3s/backend.yaml; then
  echo "后端源码或清单里出现了密钥" >&2
  exit 1
fi

manifest=deploy/k3s/backend.yaml
grep -q 'name: keel-llm' "$manifest"
grep -q 'name: keel-server' "$manifest"
test "$(grep -c 'type: ClusterIP' "$manifest")" -ge 2
if grep -Eq 'nodePort|keel-console|PLACEHOLDER_CONSOLE' "$manifest"; then
  echo "后端清单不能带控制台或对公网端口" >&2
  exit 1
fi
echo "后端清单门禁通过"
