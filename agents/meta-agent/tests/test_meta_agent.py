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
    assert loaded.spec.models.default_ == "chat-default"
    assert loaded.spec.runtime.endpoint == "http://spec-agent.agents.svc.cluster.local:8000"
    assert loaded.spec.prompts.items[0].name == "role"
    assert loaded.spec.knowledge == []
    assert loaded.spec.guardrails.inputInjection.value == "enforce"
    assert json.loads((folder / "spec.json").read_text(encoding="utf-8"))["title"] == "需求分析师"
    skeleton = load_manifest(folder / "spec-agent" / "agent.yaml")
    assert skeleton.metadata.name == "spec-agent"
    assert (folder / "spec-agent" / "app.py").is_file()
    assert (folder / "spec-agent" / "prompts/role.md").is_file()
    assert 'ctx.prompt("role")' in (folder / "spec-agent" / "app.py").read_text(encoding="utf-8")
    assert "骨架" in final["answer"]
    assert "尚未创建研发任务或远程仓库" in final["answer"]
    assert "请在控制台创建研发任务" in final["answer"]
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
    lineage = [{**REQUIREMENT, "layer": "biz"}, {**REQUIREMENT, "target_agent": "meta-agent"}]
    invalid = [{**REQUIREMENT, "target_agent": "Bad_Name"}, {**REQUIREMENT, "kind": "CHANGE"}]
    for case in lineage:
        kind, error = terminal(invoke(json.dumps(case, ensure_ascii=False)))
        assert kind == "error", case
        assert error["code"] == "DEVFLOW_LINEAGE_FORBIDDEN", case
    for case in invalid:
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


def test_gate_loads_only_the_workflow_contract_scorer():
    from keel.eval.run import load_scorers

    loaded = load_scorers(ROOT / "evals/scorers.py")
    assert [fn.__name__ for fn in loaded] == ["baseline"]
    assert loaded[0]({"errorCode": "SERVER_INVALID_PARAM"}, "SERVER_INVALID_PARAM", ["input"]) == 1.0
    assert loaded[0]({"kind": "draft"}, "{\"spec\":{},\"manifest\":{}}", ["draft-contract"]) == 0.0


def test_no_vendor_sdk_direct_imports():
    pattern = re.compile(r"^\s*(import|from)\s+(openai|dashscope|anthropic)\b", re.M)
    for path in ROOT.rglob("*.py"):
        if ".keel" in path.parts:
            continue
        assert not pattern.search(path.read_text(encoding="utf-8")), path


def test_confirmation_with_platform_services_hands_over_without_legacy_remote_writes(gateway, monkeypatch):
    monkeypatch.setenv('KEEL_SERVER_URL', 'http://127.0.0.1:9')
    monkeypatch.setenv('KEEL_GIT_CI_URL', 'http://127.0.0.1:9')
    def forbidden(*args, **kwargs):
        raise AssertionError('chat confirmation must hand over to the authorized development workflow')
    monkeypatch.setattr('keel.cli.ledger.open_job', forbidden)
    monkeypatch.setattr('keel.cli.repo.open_draft', forbidden)
    gateway.replies.append(reply())
    kind, suspended=terminal(invoke(json.dumps(REQUIREMENT, ensure_ascii=False)))
    assert kind=='suspend'
    kind, final=terminal(resume(suspended,'确认'))
    assert kind=='final', final
    assert '请在控制台创建研发任务' in final['answer']
    assert 'H1 人工确认' in final['answer']
    assert (ROOT/'.keel/meta-agent'/suspended['run_id']/'agent.yaml').is_file()
