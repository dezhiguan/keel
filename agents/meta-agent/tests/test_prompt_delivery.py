import asyncio
import importlib
import json
import sys
from types import ModuleType

import httpx
import pytest
import yaml

from candidate import parse
from workflow import _scaffold, execute, review_candidate, write_prompt_scaffold
from keel.cli.repo import _files
from keel.llm.client import LlmClient


def _candidate():
    return {"files": [
        {"path": "role.py", "content": '''async def execute(data: dict, ctx) -> dict:
    prompt = await ctx.prompt("role")
    return {"answer": prompt.compile(input=data.get("text", ""))}
'''},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}\n"},
        {"path": "tests/test_role.py", "content": '''def test_normal():
    assert True

def test_rejection():
    assert True
'''},
    ]}


def test_candidate_requires_external_prompt_and_rejects_hardcoded_instruction():
    accepted, errors = parse(json.dumps(_candidate()))
    assert errors == []
    assert accepted["files"][1]["path"] == "prompts/role.md"
    bad = _candidate()
    bad["files"][0]["content"] += "INSTRUCTION = " + repr("不要在源码里写提示词。" * 20) + "\n"
    _, errors = parse(json.dumps(bad))
    assert any("硬编码" in error for error in errors)


def test_draft_declares_prompt_knowledge_and_guardrails(monkeypatch):
    app = ModuleType("app")
    app._target = lambda requirement: requirement["target_agent"]
    app._dump = lambda value: json.dumps(value, ensure_ascii=False)
    app.RULES = "按已确认需求生成"
    monkeypatch.setitem(sys.modules, "app", app)
    monkeypatch.setenv("KEEL_LLM_BASE_URL", "https://gateway.test")
    monkeypatch.setenv("KEEL_LLM_KEY", "test-virtual-key")
    reply = {"spec": {"title": "需求分析师", "goal": "分析需求", "users": "开发者",
                      "io": "表单到文档", "success": "文档完整"}}

    def gateway(request):
        return httpx.Response(200, json={"id": "chatcmpl-draft", "object": "chat.completion",
            "created": 1, "model": "qwen-plus", "choices": [{"index": 0, "message": {"role": "assistant",
            "content": json.dumps(reply, ensure_ascii=False)}, "finish_reason": "stop"}],
            "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})

    class Prompt:
        def compile(self, **kwargs):
            return "draft"

    class Context:
        def __init__(self, llm):
            self.llm = llm

        async def prompt(self, name):
            assert name == "draft"
            return Prompt()

        def final(self, answer):
            return answer

    async def scenario():
        llm = LlmClient("meta-agent", "qwen-plus", "trace-test", http_client=httpx.AsyncClient(
            transport=httpx.MockTransport(gateway)))
        try:
            return json.loads(await execute({"action": "DRAFT", "jobId": "DF-9001",
                "requirement": {"target_agent": "spec-agent", "template": "graph-agent",
                                "title": "需求分析师", "owner": "QA", "dailyBudgetCny": 7,
                                "tools": [], "goal": "分析需求"}, "catalog": []}, Context(llm)))
        finally:
            await llm.aclose()

    draft = asyncio.run(scenario())
    spec = draft["manifest"]["spec"]
    assert spec["prompts"]["items"] == [{"name": "role", "type": "text"}]
    assert spec["knowledge"] == []
    assert spec["guardrails"] == {"inputInjection": "enforce", "grounding": "off", "pii": "mask"}
    assert yaml.safe_load(draft["manifestYaml"]) == draft["manifest"]


def test_delivery_keeps_prompt_file(tmp_path):
    project = tmp_path / "spec-agent"
    for folder in ("tools", "tests", "evals", "prompts"):
        (project / folder).mkdir(parents=True, exist_ok=True)
    (project / "requirements.lock").write_text("pytest==9.1.1\n", encoding="utf-8")
    (project / "spec.json").write_text('{"goal":"分析需求","success":"文档完整"}', encoding="utf-8")
    _scaffold(project, "DF-9001", _candidate())
    assert (project / "prompts/role.md").read_text(encoding="utf-8") == "岗位任务：{{input}}\n"
    assert "ctx.prompt(\"role\")" in (project / "role.py").read_text(encoding="utf-8")
    assert "prompts/role.md" in {item["path"] for item in _files(project)}


def test_generic_scaffold_keeps_confirmed_prompt(tmp_path):
    project = tmp_path / "spec-agent"
    for folder in ("tools", "tests", "evals"):
        (project / folder).mkdir(parents=True, exist_ok=True)
    (project / "spec.json").write_text('{"goal":"分析需求","success":"文档完整"}', encoding="utf-8")
    _scaffold(project, "DF-9001")
    assert "岗位目标：分析需求" in (project / "prompts/role.md").read_text(encoding="utf-8")
    assert 'ctx.prompt("role")' in (project / "app.py").read_text(encoding="utf-8")


def test_legacy_confirmation_normalizes_manifest_and_keeps_prompt(tmp_path, monkeypatch):
    for key, value in {"KEEL_LLM_BASE_URL": "https://gateway.test", "KEEL_LLM_KEY": "test-virtual-key",
                       "KEEL_AUDIT_URL": "https://audit.test", "KEEL_AUDIT_TOKEN": "local-dev-token",
                       "KEEL_AUDIT_SPOOL_PATH": str(tmp_path / "spool.jsonl"), "KEEL_ENV": "dev"}.items():
        monkeypatch.setenv(key, value)
    legacy = importlib.import_module("app")
    raw = {"spec": {"goal": "分析需求", "success": "文档完整"}, "manifest": {
        "apiVersion": "keel/v1", "kind": "Agent", "metadata": {"name": "spec-agent"},
        "spec": {"runtime": {"endpoint": "http://invalid.test"}, "knowledge": [{"kb": "not-approved"}]}}}
    parsed, errors = legacy._parse(json.dumps(raw))
    assert errors == []
    assert parsed["manifest"]["spec"]["knowledge"] == []
    assert parsed["manifest"]["spec"]["prompts"]["items"] == [{"name": "role", "type": "text"}]
    assert parsed["manifest"]["spec"]["guardrails"]["pii"] == "mask"
    project = tmp_path / "legacy"
    project.mkdir()
    write_prompt_scaffold(project, parsed["spec"])
    assert (project / "prompts/role.md").is_file()
    assert 'ctx.prompt("role")' in (project / "app.py").read_text(encoding="utf-8")


def test_review_fails_closed_on_hardcoded_prompt_dimension():
    files = [{"path": path, "content": "example"} for path in
             ("role.py", "app.py", "agent.yaml", "prompts/role.md", "tests/test_role.py")]
    checks = {name: True for name in ("modelAccess", "toolAuthorization", "audit",
              "visibleCaseOverfit", "releaseApproval", "behaviorTests", "hardcodedPrompt")}

    class Llm:
        async def chat(self, *args, **kwargs):
            return json.dumps({"approved": True, "checks": {**checks, "hardcodedPrompt": False},
                               "findings": []})

    request = {"delivery": {"sha": "a" * 40}, "snapshot": {"sha": "a" * 40, "files": files}}
    report = asyncio.run(review_candidate(request, type("Ctx", (), {"llm": Llm()})()))
    assert report["approved"] is False
    request["snapshot"]["files"] = [item for item in files if item["path"] != "prompts/role.md"]
    with pytest.raises(Exception, match="缺少代码评审所需文件"):
        asyncio.run(review_candidate(request, type("Ctx", (), {"llm": Llm()})()))
