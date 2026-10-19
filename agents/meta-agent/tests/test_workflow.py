import delivery
from model_context import model_context
import json
import asyncio
import httpx
import pytest

from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Route

from conftest import _serve
from test_meta_agent import invoke, terminal, reply


def work():
    return {"workflow": True, "action": "DRAFT", "jobId": "DF-9001", "version": 1,
            "requirement": {"layer": "dev", "kind": "CREATE", "target_agent": "spec-agent",
                "title": "需求分析师", "goal": "起草需求", "users": "研发同事", "io": "需求到文档",
                "success": "包含目标与边界", "template": "tool-agent", "dailyBudgetCny": 7,
                "owner": "QA", "tools": []}, "catalog": []}


def test_general_supervisor_draft_declares_selected_delegates(gateway):
    request = work()
    request["requirement"].update(target_agent="qa-coordinator", template="supervisor",
                                  delegates=["qa-ci-investigator-1017"])
    gateway.replies.append(reply(tools=[]))
    kind, event = terminal(invoke(json.dumps(request)))
    assert kind == "final"
    manifest = json.loads(event["answer"])["manifest"]
    assert manifest["spec"]["delegates"] == ["qa-ci-investigator-1017"]
    # Ignore the outdated model in the draft model response; use trusted configuration.
    assert manifest["spec"]["models"]["default"] == "chat-default"
    assert gateway.requests[0]["model"] == "chat-default"


def test_bootstrap_code_review_is_bound_to_snapshot_and_fails_closed():
    from workflow import review_candidate
    files = [{"path": path, "content": "pass\n"} for path in
             ("role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py")]
    request = {**work(), "action": "REVIEW", "delivery": {"sha": "a" * 40},
               "snapshot": {"sha": "a" * 40, "files": files}}
    checks = {name: True for name in ("modelAccess", "toolAuthorization", "audit",
              "visibleCaseOverfit", "releaseApproval", "behaviorTests", "hardcodedPrompt")}

    class Llm:
        async def chat(self, *args, **kwargs):
            return json.dumps({"approved": True, "checks": {**checks, "audit": False},
                               "findings": [{"path": "role.py", "line": 1,
                                             "severity": "HIGH", "detail": "missing audit"}]})

    class Ctx:
        llm = Llm()

    result = asyncio.run(review_candidate(request, model_context(Ctx.llm)))
    assert result["sha"] == "a" * 40
    assert result["approved"] is False
    request["snapshot"]["sha"] = "b" * 40
    with pytest.raises(Exception, match="快照与候选提交不一致"):
        asyncio.run(review_candidate(request, model_context(Ctx.llm)))


def test_bootstrap_review_receives_trusted_tool_risk():
    from workflow import review_candidate
    files = [{"path": path, "content": "pass\n"} for path in
             ("role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py")]
    request = {**work(), "action": "REVIEW", "delivery": {"sha": "a" * 40},
               "draft": {"manifest": {"spec": {"tools": [
                   {"name": "devflow.stage.report", "risk": "low", "access": "write", "approval": "none"}]}}},
               "snapshot": {"sha": "a" * 40, "files": files}}
    checks = {name: True for name in ("modelAccess", "toolAuthorization", "audit",
              "visibleCaseOverfit", "releaseApproval", "behaviorTests", "hardcodedPrompt")}

    class Llm:
        async def chat(self, messages, **kwargs):
            assert messages[0]["role"] == "system"
            assert '"trustedTools": [{"name": "devflow.stage.report", "risk": "low"' in messages[1]["content"]
            return json.dumps({"approved": True, "checks": checks, "findings": []})

    class Ctx:
        llm = Llm()

    assert asyncio.run(review_candidate(request, model_context(Ctx.llm)))["approved"] is True


def test_bootstrap_review_rejects_unactionable_failure():
    from workflow import review_candidate
    files = [{"path": path, "content": "pass\n"} for path in
             ("role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py")]
    checks = {name: True for name in ("modelAccess", "toolAuthorization", "audit",
              "visibleCaseOverfit", "releaseApproval", "behaviorTests", "hardcodedPrompt")}

    class Llm:
        async def chat(self, *args, **kwargs):
            return json.dumps({"approved": False, "checks": {**checks, "visibleCaseOverfit": False},
                               "findings": []})

    request = {**work(), "action": "REVIEW", "delivery": {"sha": "a" * 40},
               "snapshot": {"sha": "a" * 40, "files": files}}
    with pytest.raises(Exception, match="未返回有效结构化结论"):
        asyncio.run(review_candidate(request, model_context(Llm())))


def test_bootstrap_review_rechecks_findings_against_original_contract_and_source():
    from workflow import review_candidate
    files = [{"path": path, "content": "pass\n"} for path in
             ("role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py")]
    checks = {name: True for name in ("modelAccess", "toolAuthorization", "audit",
              "visibleCaseOverfit", "releaseApproval", "behaviorTests", "hardcodedPrompt")}
    calls = []

    class Llm:
        async def chat(self, messages, **kwargs):
            assert [m["role"] for m in messages] == ["system", "user"]
            calls.append(messages[1]["content"])
            if len(calls) == 1:
                return json.dumps({"approved": False, "checks": {**checks, "behaviorTests": False},
                                   "findings": [{"path": "role.py", "line": 1, "severity": "HIGH",
                                                 "detail": "unsupported initial claim"}]})
            assert '"trustedSpec": {"io": "return evidence"}' in calls[-1]
            assert "unsupported initial claim" in calls[-1]
            data = json.loads(calls[-1])
            assert "files" not in data and "numberedFiles" not in data
            assert data["findingEvidence"][0]["source"] == "1: pass"
            assert data["findingEvidence"][0]["finding"]["detail"] == "unsupported initial claim"
            assert data["runtimeContract"]["executeResult"]["additionalProperties"] is True
            assert data["runtimeContract"]["promptOutputScope"].startswith("ctx.llm.chat response only")
            assert "混淆模型与岗位输出契约" in messages[0]["content"]
            return json.dumps({"approved": True, "checks": checks, "findings": []})

    request = {**work(), "action": "REVIEW", "delivery": {"sha": "a" * 40},
               "draft": {"spec": {"io": "return evidence"}},
               "snapshot": {"sha": "a" * 40, "files": files}}
    assert asyncio.run(review_candidate(request, model_context(Llm())))["approved"]
    assert len(calls) == 2


def test_workflow_drafts_and_revises_without_an_sdk_resume_token(gateway):
    gateway.replies.append(reply(tools=[]))
    kind, event = terminal(invoke(json.dumps(work())))
    assert kind == "final"
    draft = json.loads(event["answer"])
    assert draft["manifest"]["spec"]["models"]["budget"]["dailyCny"] == 7
    assert draft["manifest"]["metadata"]["owner"] == "QA"
    assert draft["manifest"]["spec"]["prompts"]["items"] == [{"name": "role", "type": "text"}]
    assert draft["manifest"]["spec"]["knowledge"] == []
    assert draft["manifest"]["spec"]["guardrails"]["inputInjection"] == "enforce"
    assert isinstance(draft["spec"]["pending"], list)
    assert "language: python" in draft["manifestYaml"]
    revised = {**work(), "draft": draft, "feedback": "增加失败场景", "version": 2}
    gateway.replies.append(reply(tools=[]))
    assert terminal(invoke(json.dumps(revised)))[0] == "final"
    assert "增加失败场景" in gateway.requests[-1]["messages"][0]["content"]


def test_workflow_delivery_preserves_edits_and_does_not_repeat_git_writes(gateway, monkeypatch, tmp_path):
    from tools import state
    monkeypatch.setattr(state, "ROOT", tmp_path)
    gateway.replies.append(reply(tools=[]))
    _, event = terminal(invoke(json.dumps(work())))
    draft = json.loads(event["answer"])
    draft["spec"]["users"] = "人工确认后的使用者"
    calls = []

    async def git(request):
        assert request.headers["authorization"] == "Bearer test-git-token"
        body = await request.json()
        calls.append((request.path_params["tool"], body))
        return JSONResponse({"repo": "keel-agents/spec-agent", "sha": "a" * 40, "number": 1,
                             "url": "https://github.com/keel-agents/spec-agent/pull/1"})

    port = _serve(Starlette(routes=[Route("/v1/tools/{tool}", git, methods=["POST"])]))
    monkeypatch.setenv("KEEL_GIT_CI_URL", f"http://127.0.0.1:{port}")
    monkeypatch.setenv("KEEL_GIT_CI_TOKEN", "test-git-token")
    request = {**work(), "action": "DELIVER", "draft": draft}
    import workflow
    original_post = delivery._post
    failed_once = False

    def transient_git_disconnect(base, tool, body):
        nonlocal failed_once
        if tool == "git.pr.open" and not failed_once:
            failed_once = True
            raise httpx.ConnectError("transient Git connection failure")
        return original_post(base, tool, body)

    monkeypatch.setattr(delivery, "_post", transient_git_disconnect)
    kind, event = terminal(invoke(json.dumps(request)))
    assert kind == "error"
    assert event["code"] == "GIT_UPSTREAM_FAILED"
    kind, event = terminal(invoke(json.dumps(request)))
    assert kind == "final"
    assert json.loads(event["answer"])["number"] == 1
    assert [tool for tool, _ in calls] == ["git.repo.create", "git.branch.push", "git.pr.open"]
    files = {item["path"]: item["content"] for item in calls[1][1]["files"]}
    assert json.loads(files["spec.json"])["users"] == "人工确认后的使用者"
    assert files["agent.yaml"] == draft["manifestYaml"]
    assert "app.py" in files
    assert "prompts/role.md" in files
    assert "create_work_order" not in files["app.py"]
    assert files["evals/seed.jsonl"] == ""
    assert "manifest.schema.json" not in files
    assert "contracts/manifest.schema.json" not in files
    assert "AGENTS.md" in files
    assert "pyproject.toml" in files
    assert ".github/workflows/keel.yml" in files
    assert "ci/keel.yml" not in files
    assert "on: [push, pull_request]" in files[".github/workflows/keel.yml"]
    assert json.loads(event["answer"])["checks"] == "ENABLED"
    import subprocess, sys
    checked = subprocess.run([sys.executable, "-m", "pytest", "tests", "-q"],
                             cwd=tmp_path / "DF-9001/spec-agent", capture_output=True, text=True)
    assert checked.returncode == 0, checked.stdout + checked.stderr
    assert terminal(invoke(json.dumps(request)))[0] == "final"
    assert len(calls) == 3


def test_bridge_token_rejects_unauthorized_work(monkeypatch):
    from starlette.testclient import TestClient
    from app import app
    monkeypatch.setenv("KEEL_META_AGENT_TOKEN", "test-bridge")
    with TestClient(app) as client:
        assert client.post("/v1/invoke", json={"input": {"text": json.dumps(work())}}).status_code == 401
        assert client.get("/v1/health").status_code == 200


def test_model_cannot_add_unselected_tools_or_change_identity(gateway):
    bad = json.loads(reply(tools=[]))
    bad["spec"]["goal"] = "擅自改成自动合并 PR"
    bad["manifest"]["metadata"]["name"] = "wrong-agent"
    bad["manifest"]["spec"]["tools"] = [{"name": "git.pr.merge", "access": "write", "risk": "low"}]
    bad["manifest"]["spec"]["knowledge"] = [{"kb": "unapproved"}]
    gateway.replies.append(json.dumps(bad))
    request = {**work(), "catalog": [{"name": "git.pr.merge", "access": "WRITE", "risk": "HIGH"}]}
    kind, event = terminal(invoke(json.dumps(request)))
    assert kind == "final"
    result = json.loads(event["answer"])
    assert result["spec"]["goal"] == request["requirement"]["goal"]
    assert result["manifest"]["metadata"]["name"] == "spec-agent"
    assert result["manifest"]["spec"]["tools"] == []
    assert result["manifest"]["spec"]["knowledge"] == []
    assert len(gateway.requests) == 1


def test_selected_tool_uses_registered_risk_and_approval(gateway):
    request = work()
    request["requirement"]["tools"] = ["git.pr.merge"]
    request["catalog"] = [{"name": "git.pr.merge", "access": "WRITE", "risk": "HIGH"}]
    gateway.replies.append(reply(tools=[]))
    kind, event = terminal(invoke(json.dumps(request)))
    assert kind == "final"
    assert json.loads(event["answer"])["manifest"]["spec"]["tools"] == [
        {"name": "git.pr.merge", "access": "write", "risk": "high", "approval": "required"}]
