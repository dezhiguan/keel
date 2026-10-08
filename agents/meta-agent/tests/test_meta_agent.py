import asyncio
import json
import re
from pathlib import Path

import httpx

from app import app
from keel.manifest import load_manifest

ROOT = Path(__file__).resolve().parents[1]
REQUIREMENT = {"layer": "dev", "kind": "CREATE", "target_agent": "spec-agent", "title": "需求分析师",
               "goal": "把需求表单变成需求单和 agent.yaml 草稿", "tools": ["devflow.catalog.search"]}


def manifest(name="spec-agent", tools=None):
    return {
        "apiVersion": "keel/v1", "kind": "Agent",
        "metadata": {"name": name, "displayName": "需求分析师", "owner": "研发效能组 / amy"},
        "spec": {
            "runtime": {"type": "code", "language": "python", "liveness": "k8s"},
            "auth": {"audience": name},
            "models": {"default": "qwen-plus", "budget": {"dailyCny": 20}},
            "tools": tools if tools is not None else [
                {"name": "devflow.catalog.search", "access": "read", "risk": "low", "approval": "none"}],
            "audit": {"captureFields": ["target_agent"]},
            "eval": {"dataset": f"{name}/seed"},
        },
    }


def reply(name="spec-agent", tools=None, title="需求分析师"):
    return json.dumps({"spec": {"title": title, "goal": "起草需求单", "users": "研发流水线", "io": "表单 → 需求单",
                                "success": "一次通过率 ≥ 90%", "risks": [], "pending": ["预算待定"]},
                       "manifest": manifest(name, tools)}, ensure_ascii=False)


def events(response) -> list[tuple[str, dict]]:
    found, name = [], None
    for line in response.text.splitlines():
        if line.startswith("event: "):
            name = line.removeprefix("event: ")
        elif line.startswith("data: ") and name:
            found.append((name, json.loads(line.removeprefix("data: "))))
            name = None
    return found


def terminal(response) -> tuple[str, dict]:
    return next(item for item in reversed(events(response)) if item[0] in ("suspend", "final", "error"))


async def _post(path, body):
    async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
        return await client.post(path, json=body)


def invoke(text):
    return asyncio.run(_post("/v1/invoke", {"input": {"text": text}}))


def resume(suspended: dict, text: str):
    return asyncio.run(_post(f"/v1/runs/{suspended['run_id']}/resume",
                             {"resume_token": suspended["resume_token"], "input": {"text": text}}))


def state_of(run_id):
    return json.loads((ROOT / ".keel/meta-agent" / run_id / "state.json").read_text(encoding="utf-8"))


def test_draft_then_confirm_writes_a_loadable_manifest(gateway):
    gateway.replies.append(reply())
    kind, suspended = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    assert kind == "suspend"
    assert suspended["reason"] == "input_required"
    assert suspended["ref"] == suspended["run_id"]
    assert "agent.yaml" in suspended["prompt"]

    kind, final = terminal(resume(suspended, " 确认 "))
    assert kind == "final"
    folder = ROOT / ".keel/meta-agent" / suspended["run_id"]
    loaded = load_manifest(folder / "agent.yaml")
    assert loaded.metadata.name == "spec-agent"
    assert loaded.spec.runtime.endpoint == "http://spec-agent.agents.svc.cluster.local:8000"
    assert json.loads((folder / "spec.json").read_text(encoding="utf-8"))["title"] == "需求分析师"
    assert len(gateway.requests) == 1


def test_feedback_produces_next_version_with_previous_draft(gateway):
    gateway.replies += [reply(), reply(title="需求分析师 v2")]
    _, suspended = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    kind, again = terminal(resume(suspended, "预算改成每天 10 元"))
    assert kind == "suspend"
    assert again["run_id"] == suspended["run_id"]
    state = state_of(suspended["run_id"])
    assert state["version"] == 2
    assert state["revisions"] == 1
    sent = gateway.requests[1]["messages"][0]["content"]
    assert "预算改成每天 10 元" in sent
    assert "需求分析师" in sent


def test_stops_after_three_revisions(gateway):
    gateway.replies += [reply()] * 4
    _, suspended = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    for round_ in range(3):
        kind, suspended = terminal(resume(suspended, f"第 {round_ + 1} 次意见"))
        assert kind == "suspend"
    kind, final = terminal(resume(suspended, "还是不对"))
    assert kind == "final"
    assert "已改 3 轮" in final["answer"]
    assert len(gateway.requests) == 4


def test_invalid_draft_is_retried_once(gateway):
    broken = manifest(tools=[{"name": "devflow.catalog.search", "risk": "high"}])
    gateway.replies += [json.dumps({"spec": {}, "manifest": broken}), reply()]
    kind, _ = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    assert kind == "suspend"
    assert len(gateway.requests) == 2
    feedback = gateway.requests[1]["messages"][-1]["content"]
    assert "approval: required" in feedback


def test_two_invalid_drafts_end_with_manifest_invalid(gateway):
    gateway.replies += ["不是 JSON", "还是不是"]
    kind, error = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    assert kind == "error"
    assert error["code"] == "AGENT_MANIFEST_INVALID"
    assert len(gateway.requests) == 2


def test_lineage_and_name_rules_reject_before_calling_the_model(gateway):
    cases = [{**REQUIREMENT, "layer": "biz"}, {**REQUIREMENT, "target_agent": "meta-agent"},
             {**REQUIREMENT, "target_agent": "Bad_Name"}, {**REQUIREMENT, "kind": "CHANGE"}]
    for case in cases:
        kind, error = terminal(invoke(json.dumps(case, ensure_ascii=False)))
        assert kind == "error", case
        assert error["code"] == "SERVER_INVALID_PARAM", case
    assert gateway.requests == []


def test_model_cannot_rename_the_target(gateway):
    gateway.replies += [reply(name="other-agent"), reply(name="other-agent")]
    kind, error = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    assert kind == "error"
    assert "metadata.name 应为 spec-agent" in error["message"]


def test_catalog_blocks_lower_risk_than_registered(gateway, catalog):
    catalog([{"name": "git.pr.merge", "risk": "high", "owner": "研发效能组"}])
    lowered = [{"name": "git.pr.merge", "access": "write", "risk": "low", "approval": "none"}]
    gateway.replies += [reply(tools=lowered), reply(tools=lowered)]
    kind, error = terminal(invoke(json.dumps({**REQUIREMENT, "tools": ["git.pr.merge"]}, ensure_ascii=False)))
    assert kind == "error"
    assert "风险不能低于登记值 high" in error["message"]


def test_without_catalog_the_sheet_says_tools_are_unchecked(gateway):
    gateway.replies.append(reply())
    _, suspended = terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    assert "未核对注册表" in suspended["prompt"]


def test_plain_text_requirement_is_accepted(gateway):
    gateway.replies.append(reply())
    kind, _ = terminal(invoke("帮我做一个把需求表单变成需求单的研发智能体，叫 spec-agent"))
    assert kind == "suspend"
    assert "把需求表单变成需求单" in gateway.requests[0]["messages"][0]["content"]


def test_scorers_read_the_yaml_block_of_the_final_answer():
    import yaml
    from evals.scorers import manifest_valid, tool_risk_not_lowered

    body = manifest(tools=[{"name": "git.pr.merge", "access": "write", "risk": "high", "approval": "required"}])
    body["spec"]["runtime"]["endpoint"] = "http://spec-agent.agents.svc.cluster.local:8000"
    output = "## 需求单\n\n```yaml\n" + yaml.safe_dump(body, allow_unicode=True) + "```"
    assert manifest_valid(output, {"target_agent": "spec-agent"}, []) == 1.0
    assert manifest_valid("没有 yaml", {"target_agent": "spec-agent"}, []) == 0.0
    assert tool_risk_not_lowered(output, {"tools": {"git.pr.merge": "high"}}, []) == 1.0
    assert tool_risk_not_lowered(output, {"tools": {"git.pr.merge": "high", "ci.log.fetch": "low"}}, []) == 0.5


def test_no_vendor_sdk_direct_imports():
    pattern = re.compile(r"^\s*(import|from)\s+(openai|dashscope|anthropic)\b", re.M)
    for path in ROOT.rglob("*.py"):
        if ".keel" in path.parts:
            continue
        assert not pattern.search(path.read_text(encoding="utf-8")), path
