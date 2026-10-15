#!/usr/bin/env bash
# Create the spec-agent job, or later read its evidence. No default host.
# create posts once. collect only sends GET. Neither approves H4 or merges a PR.
set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
cd "$root"

mode="${1:-}"
if [[ "$mode" == "--dry-run" ]]; then
  echo "+ POST \$KEEL_SERVER_URL/api/v1/devflow/jobs  # job.json：spec-agent / graph-agent / COLLAB"
  echo "+ 人工关口留在控制台：H1 确认、H2 种子、H3 授权、PR 评审、代码评审、H4"
  echo "+ 本脚本不申请 H4，也不提交审批决定"
  echo "+ GET \$KEEL_SERVER_URL/api/v1/devflow/jobs/\$KEEL_JOB_ID"
  echo "+ GET \$KEEL_SERVER_URL/api/v1/devflow/jobs/\$KEEL_JOB_ID/execution"
  echo "+ GET \$KEEL_SERVER_URL/api/v1/agents?category=dev"
  echo "+ GET \$KEEL_SERVER_URL/api/v1/devflow/jobs  # status=DONE 即总览「本月上线」"
  echo "+ 观察满 7 个健康日后再 collect，未满不要写成通过"
  exit 0
fi
if [[ "$mode" != "create" && "$mode" != "collect" ]]; then
  echo "用法: $0 --dry-run | create | collect" >&2
  exit 2
fi

require() {
  if [[ -z "${!1:-}" ]]; then
    echo "缺少环境变量 $1" >&2
    exit 2
  fi
}

require KEEL_SERVER_URL
require KEEL_CONSOLE_COOKIE
case "$KEEL_SERVER_URL" in
  */|*api/v1) echo "KEEL_SERVER_URL 不要以 / 结尾，也不要带 /api/v1" >&2; exit 2 ;;
esac
case "$KEEL_CONSOLE_COOKIE" in
  *__Host-keel_console_at=*|keel_console_at=*) ;;
  *) echo "KEEL_CONSOLE_COOKIE 必须是 __Host-keel_console_at=... 或 keel_console_at=..." >&2; exit 2 ;;
esac

mkdir -p evidence
api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$KEEL_SERVER_URL$path" \
      -H "Cookie: $KEEL_CONSOLE_COOKIE" -H "Content-Type: application/json" \
      --data-binary "$body"
  else
    curl -fsS -X "$method" "$KEEL_SERVER_URL$path" -H "Cookie: $KEEL_CONSOLE_COOKIE"
  fi
}

if [[ "$mode" == "create" ]]; then
  python3 - <<'PY'
import json
from pathlib import Path
job = json.loads(Path("job.json").read_text(encoding="utf-8"))
tools = set(job.get("tools") or [])
budget = job.get("dailyBudgetCny")
if job.get("targetAgent") != "spec-agent" or job.get("template") != "graph-agent":
    raise SystemExit("岗位必须是 spec-agent，模板必须是 graph-agent")
if job.get("layer") != "DEV" or job.get("kind") != "CREATE" or job.get("mode") != "COLLAB":
    raise SystemExit("必须是 DEV / CREATE / COLLAB")
if job.get("execution") is not True:
    raise SystemExit("必须显式 execution=true，交给元智能体")
if tools != {"devflow.catalog.search", "devflow.stage.report"}:
    raise SystemExit("工具必须与原型清单一致")
if job.get("knowledge") or job.get("seedCount"):
    raise SystemExit("不要在创建时填知识库或种子数量")
if not isinstance(budget, (int, float)) or budget < 1:
    raise SystemExit("日预算必须大于等于 1")
PY
  echo "+ POST /api/v1/devflow/jobs"
  api POST /api/v1/devflow/jobs "$(python3 -c 'print(open("job.json", encoding="utf-8").read())')" | tee evidence/create.json
  python3 - <<'PY'
import json
from pathlib import Path
body = json.loads(Path("evidence/create.json").read_text(encoding="utf-8"))
job_id = body.get("data", {}).get("jobId")
if not job_id:
    raise SystemExit("创建响应里没有 jobId")
print(job_id)
print("已提交。H1、H2、H3、PR 评审和 H4 在控制台由人完成。本脚本不批准发布。")
PY
  exit 0
fi

require KEEL_JOB_ID
echo "+ GET /api/v1/devflow/jobs/$KEEL_JOB_ID"
api GET "/api/v1/devflow/jobs/$KEEL_JOB_ID" | tee evidence/job-detail.json >/dev/null
echo "+ GET /api/v1/devflow/jobs/$KEEL_JOB_ID/execution"
api GET "/api/v1/devflow/jobs/$KEEL_JOB_ID/execution" | tee evidence/execution.json >/dev/null
echo "+ GET /api/v1/agents?category=dev&page=1&size=100"
api GET "/api/v1/agents?category=dev&page=1&size=100" | tee evidence/agents.json >/dev/null
echo "+ GET /api/v1/devflow/jobs"
api GET "/api/v1/devflow/jobs" | tee evidence/board.json >/dev/null
KEEL_JOB_ID="$KEEL_JOB_ID" python3 - <<'PY'
import json, os
from pathlib import Path
job = json.loads(Path("evidence/job-detail.json").read_text(encoding="utf-8")).get("data") or {}
execution = json.loads(Path("evidence/execution.json").read_text(encoding="utf-8")).get("data") or {}
agents = json.loads(Path("evidence/agents.json").read_text(encoding="utf-8")).get("data") or {}
board = json.loads(Path("evidence/board.json").read_text(encoding="utf-8")).get("data") or {}
job_id = os.environ["KEEL_JOB_ID"]
items = agents.get("items") or []
matched = [row for row in items if row.get("name") == "spec-agent" and row.get("devflowJobId") == job_id and row.get("layer") == "DEV"]
done = [row for row in (board.get("items") or []) if row.get("status") == "DONE"]
phase = execution.get("phase") or ""
observations = execution.get("watchObservations") or []
lines = [
    f"jobId={job_id}",
    f"jobStatus={job.get('status')}",
    f"phase={phase}",
    f"specAgentInCatalog={'yes' if matched else 'no'}",
    f"doneCount={len(done)}",
    f"watchDays={len(observations)}",
]
if phase == "DONE" and matched and len(observations) >= 7:
    lines.append("acceptance=证据齐，由操作者写入报告")
else:
    lines.append("acceptance=未满足")
Path("evidence/verdict.txt").write_text("\n".join(lines) + "\n", encoding="utf-8")
print("\n".join(lines))
PY
