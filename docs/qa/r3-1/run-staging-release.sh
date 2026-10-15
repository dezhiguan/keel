#!/usr/bin/env bash
# Run staging gate, one blocked gate, then release staging and prod.
# The server origin comes only from KEEL_SERVER_URL. This script has no default host.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$root"

dry=0
if [[ "${1:-}" == "--dry-run" ]]; then
  dry=1
elif [[ $# -ne 0 ]]; then
  echo "用法: $0 [--dry-run]" >&2
  exit 2
fi

require() {
  if [[ -z "${!1:-}" ]]; then
    echo "缺少环境变量 $1" >&2
    exit 2
  fi
}

run() {
  printf '+'
  printf ' %q' "$@"
  printf '\n'
  if [[ "$dry" -eq 1 ]]; then
    return 0
  fi
  "$@"
}

if [[ "$dry" -eq 0 ]]; then
  require KEEL_SERVER_URL
  require KEEL_AGENT_ENDPOINT
  require KEEL_RELEASE_IMAGE
  case "$KEEL_SERVER_URL" in
    */) echo "KEEL_SERVER_URL 不要以 / 结尾，也不要带 /api/v1" >&2; exit 2 ;;
    */api/v1|*//api/v1) echo "KEEL_SERVER_URL 不要带 /api/v1" >&2; exit 2 ;;
  esac
  yaml_endpoint=$(python3 -c 'import yaml,sys; print(yaml.safe_load(open("agent.yaml"))["spec"]["runtime"]["endpoint"])')
  if [[ "$yaml_endpoint" != "$KEEL_AGENT_ENDPOINT" ]]; then
    echo "agent.yaml 的 endpoint 必须等于 KEEL_AGENT_ENDPOINT，脚本不改 manifest" >&2
    exit 2
  fi
  mkdir -p evidence
fi

echo "失败门禁用 evals/seed-fail.jsonl，期望被 exact 判 0 分并退出 1"
if [[ "$dry" -eq 1 ]]; then
  echo "+ keel register --env staging --file agent.yaml"
  echo "+ keel gate --env staging --file evals/seed-fail.jsonl --endpoint \"\$KEEL_AGENT_ENDPOINT\"  # 必须失败"
  echo "+ keel gate --env staging --file evals/seed.jsonl --endpoint \"\$KEEL_AGENT_ENDPOINT\""
  echo "+ keel release --env staging --image \"\$KEEL_RELEASE_IMAGE\""
  echo "+ keel release --env prod --image \"\$KEEL_RELEASE_IMAGE\""
  echo "+ curl \$KEEL_SERVER_URL/api/v1/insight/traces?agent=qa-r3-1"
  echo "+ curl \$KEEL_SERVER_URL/api/v1/audit/events?agent=qa-r3-1"
  exit 0
fi

run keel register --env staging --file agent.yaml | tee evidence/register.txt

set +e
keel gate --env staging --file evals/seed-fail.jsonl --endpoint "$KEEL_AGENT_ENDPOINT" | tee evidence/gate-fail.txt
fail_status=${PIPESTATUS[0]}
set -e
if [[ "$fail_status" -eq 0 ]]; then
  echo "失败门禁不应通过，已中止，不会发布" >&2
  exit 1
fi
if [[ -f .keel/gate-run-id ]]; then
  echo "失败门禁不应留下 gateRunId" >&2
  exit 1
fi

run keel gate --env staging --file evals/seed.jsonl --endpoint "$KEEL_AGENT_ENDPOINT" | tee evidence/gate-pass.txt
run keel release --env staging --image "$KEEL_RELEASE_IMAGE" | tee evidence/release-staging.txt
run keel release --env prod --image "$KEEL_RELEASE_IMAGE" | tee evidence/release-prod.txt

curl -fsS "$KEEL_SERVER_URL/api/v1/insight/traces?agent=qa-r3-1" -o evidence/traces.json || echo "trace 列表没有拉到，请按清单手工保存" >&2
curl -fsS "$KEEL_SERVER_URL/api/v1/audit/events?agent=qa-r3-1" -o evidence/audit.json || echo "审计列表没有拉到，请按清单手工保存" >&2
echo "证据在 $root/evidence 。把 gateRunId、trace、审计和两次发布响应抄进 docs/qa/2026-10-15-r3-1-release.md"
