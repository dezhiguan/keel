#!/usr/bin/env bash
# Suspend a high-risk tool, wait past the 10 minute exchange token, approve, then resume.
# KEEL_SERVER_URL has no default. The script never reads a private key.
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

if [[ "$dry" -eq 1 ]]; then
  echo "+ POST \$KEEL_AGENT_ENDPOINT/v1/invoke  # 高风险工具挂起"
  echo "+ sleep \${R3_WAIT_SECONDS:-660}  # 必须至少 600 秒，覆盖 600 秒换票"
  echo "+ POST \$KEEL_SERVER_URL/api/v1/approvals/{id}/decision decision=APPROVE"
  echo "+ POST \$KEEL_AGENT_ENDPOINT/v1/runs/{run_id}/resume resume_token decision=approve consent_id=\$KEEL_CONSENT_ID"
  echo "+ curl \$KEEL_SERVER_URL/api/v1/audit/events?agent=qa-r3-2"
  echo "+ curl \$KEEL_SERVER_URL/api/v1/insight/traces?agent=qa-r3-2"
  exit 0
fi

require KEEL_SERVER_URL
require KEEL_AGENT_ENDPOINT
require KEEL_CONSENT_ID
wait_seconds="${R3_WAIT_SECONDS:-660}"
if [[ "$wait_seconds" -lt 600 ]]; then
  echo "R3_WAIT_SECONDS 必须至少 600，否则不能证明超过 10 分钟后重新换票" >&2
  exit 2
fi
case "$KEEL_SERVER_URL" in
  */|*api/v1) echo "KEEL_SERVER_URL 不要以 / 结尾，也不要带 /api/v1" >&2; exit 2 ;;
esac

mkdir -p evidence
invoke=$(mktemp)
curl -fsS -N -X POST "$KEEL_AGENT_ENDPOINT/v1/invoke" \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d '{"input":{"text":"export"}}' | tee evidence/suspend.sse | tee "$invoke"
python3 - "$invoke" <<'PY'
import json, sys
from pathlib import Path
raw = Path(sys.argv[1]).read_text(encoding="utf-8")
event, data = "", []
found = None
for line in raw.splitlines():
    if line == "":
        if data and event == "suspend":
            found = json.loads("\n".join(data))
        event, data = "", []
        continue
    if line.startswith("event:"):
        event = line.split(":", 1)[1].strip()
    elif line.startswith("data:"):
        data.append(line.split(":", 1)[1].strip())
if found is None:
    raise SystemExit("没有 suspend 事件")
Path("evidence/suspend.json").write_text(json.dumps(found, ensure_ascii=False, indent=2), encoding="utf-8")
print(found["run_id"])
print(found["resume_token"])
print(found.get("ref") or "")
print(found.get("trace_id") or "")
PY
parsed=$(python3 -c 'import json; d=json.load(open("evidence/suspend.json")); print(d["run_id"]); print(d["resume_token"]); print(d.get("ref") or ""); print(d.get("trace_id") or "")')
run_id=$(printf '%s\n' "$parsed" | sed -n '1p')
resume_token=$(printf '%s\n' "$parsed" | sed -n '2p')
approval_id=$(printf '%s\n' "$parsed" | sed -n '3p')
trace_id=$(printf '%s\n' "$parsed" | sed -n '4p')
rm -f "$invoke"
if [[ -z "$approval_id" ]]; then
  echo "suspend 事件没有审批单号 ref" >&2
  exit 1
fi

echo "等待 ${wait_seconds} 秒后再批准，使原来的 600 秒换票失效"
sleep "$wait_seconds"

curl -fsS -X POST "$KEEL_SERVER_URL/api/v1/approvals/${approval_id}/decision" \
  -H "Content-Type: application/json" \
  -d '{"decision":"APPROVE"}' | tee evidence/approval.json

body=$(RESUME_TOKEN="$resume_token" python3 -c 'import json,os; print(json.dumps({"resume_token":os.environ["RESUME_TOKEN"],"decision":"approve","consent_id":os.environ["KEEL_CONSENT_ID"]}))')
curl -fsS -N -X POST "$KEEL_AGENT_ENDPOINT/v1/runs/${run_id}/resume" \
  -H "Content-Type: application/json" -H "Accept: text/event-stream" \
  -d "$body" | tee evidence/resume.sse

curl -fsS "$KEEL_SERVER_URL/api/v1/audit/events?agent=qa-r3-2" -o evidence/audit.json || echo "审计列表没有拉到，请按清单手工保存" >&2
curl -fsS "$KEEL_SERVER_URL/api/v1/insight/traces?agent=qa-r3-2" -o evidence/traces.json || echo "trace 列表没有拉到，请按清单手工保存" >&2
echo "证据在 $root/evidence 。trace_id=${trace_id} run_id=${run_id}。把挂起、审批、恢复三段和这条链路抄进报告。"
