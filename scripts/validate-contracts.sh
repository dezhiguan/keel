#!/usr/bin/env bash
# 校验 contracts/ 下的契约文件和示例。
#
# 做三件事：
#   1. 三个 JSON Schema 自身合法（draft 2020-12）
#   2. invoke.openapi.yaml 通过 redocly lint
#   3. examples/ 下的正例全部通过、反例全部被拒
#
# 反例这一步是重点：只验正例的话，schema 写得过于宽松也会全绿。
set -euo pipefail
cd "$(dirname "$0")/.."

PY="${PY:-.venv/bin/python}"
[ -x "$PY" ] || { echo "缺 $PY。先跑：python3 -m venv .venv && .venv/bin/pip install jsonschema pyyaml"; exit 1; }

echo "── 1/3 Schema 自身语法 ─────────────────────────"
"$PY" scripts/check_contracts.py schema

echo
echo "── 2/3 OpenAPI lint ────────────────────────────"
npx -y @redocly/cli@latest lint --config contracts/redocly.yaml contracts/invoke.openapi.yaml

echo
echo "── 3/3 正例与反例 ──────────────────────────────"
"$PY" scripts/check_contracts.py examples
