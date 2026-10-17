import asyncio
import ast
import json
import subprocess
import sys

import yaml

from candidate import generate, parse
from keel.eval.run import load_scorers
from workflow import _scaffold
from test_meta_agent import manifest


ROLE_CODE = '''import json

async def execute(data: dict, ctx) -> dict:
    requirement = data.get("requirement")
    if not requirement:
        return {"status": "BLOCKED", "reason": "requirement missing"}
    prompt = await ctx.prompt("role")
    reply = await ctx.llm.chat([{"role": "user", "content": prompt.compile(input=requirement)}], prompt=prompt)
    return {"status": "READY", "spec": json.loads(reply)}
'''
TEST_CODE = '''import asyncio
from role import execute

class FakeLlm:
    async def chat(self, messages, **kwargs):
        return '{"goal":"done"}'

class FakePrompt:
    def compile(self, **values):
        return values["input"]

class FakeCtx:
    llm = FakeLlm()
    async def prompt(self, name):
        assert name == "role"
        return FakePrompt()

def test_requirement_is_analyzed():
    result = asyncio.run(execute({"requirement": "draft a release note"}, FakeCtx()))
    assert result["spec"]["goal"] == "done"

def test_missing_requirement_is_blocked():
    result = asyncio.run(execute({}, FakeCtx()))
    assert result["status"] == "BLOCKED"
'''


class FakeModel:
    def __init__(self, replies):
        self.replies = iter(replies)
        self.prompts = []
    async def chat(self, messages, **kwargs):
        self.prompts.append(messages[0]["content"])
        return next(self.replies)


def test_meta_agent_generates_role_code_from_confirmed_spec():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    llm = FakeModel([json.dumps(candidate)])
    ctx = type("Ctx", (), {"llm": llm})()
    work = {"requirement": {"target_agent": "spec-agent", "template": "graph-agent", "tools": []},
            "draft": {"spec": {"goal": "把真实需求变成结构化需求单"}}}
    output = asyncio.run(generate(work, ctx))
    assert output == candidate
    assert "把真实需求变成结构化需求单" in llm.prompts[0]
    assert "spec-agent" in llm.prompts[0]


def test_meta_agent_generates_general_agent_from_confirmed_goal():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    llm = FakeModel([json.dumps(candidate)])
    ctx = type("Ctx", (), {"llm": llm})()
    work = {"requirement": {"target_agent": "qa-incident-helper", "template": "tool-agent",
                            "goal": "分析故障并调用日志工具", "tools": ["ci.log.fetch"]},
            "draft": {"spec": {"goal": "按证据给出故障原因"}}}
    assert asyncio.run(generate(work, ctx)) == candidate
    assert "qa-incident-helper" in llm.prompts[0]
    assert "分析故障并调用日志工具" in llm.prompts[0]


def test_generated_code_rejects_forbidden_imports_and_missing_behavior_tests():
    bad = {"files": [{"path": "role.py", "content": "import subprocess\nasync def execute(data, ctx): return {}"},
                     {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                     {"path": "tests/test_role.py", "content": "def test_only_one(): pass"}]}
    _, errors = parse(json.dumps(bad))
    assert any("导入" in error for error in errors)
    assert any("正常和拒绝" in error for error in errors)


def test_generated_python_rejects_lowercase_json_literals():
    bad = {"files": [{"path": "role.py", "content": ROLE_CODE},
                     {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                     {"path": "tests/test_role.py", "content": TEST_CODE + "\ndef test_bad_bool():\n    assert {\"ok\": false}\n"}]}
    _, errors = parse(json.dumps(bad))
    assert any("tests/test_role.py" in error and "JSON 小写" in error for error in errors)
    assert parse(json.dumps(bad), allow_legacy_literals=True)[1] == []


def test_model_authored_role_runs_in_generated_project(tmp_path):
    project = tmp_path / "spec-agent"
    for name in ("tools", "tests", "evals"):
        (project / name).mkdir(parents=True, exist_ok=True)
    (project / "requirements.lock").write_text("pytest==9.1.1\n", encoding="utf-8")
    configured = manifest()
    configured["spec"]["runtime"]["endpoint"] = "http://spec-agent.agents.svc.cluster.local:8000"
    (project / "agent.yaml").write_text(yaml.safe_dump(configured, sort_keys=False), encoding="utf-8")
    (project / "spec.json").write_text(json.dumps({"title": "需求分析师"}), encoding="utf-8")
    old_workflow = project / ".github/workflows/keel.yml"
    old_workflow.parent.mkdir(parents=True, exist_ok=True)
    old_workflow.write_text("invalid: [", encoding="utf-8")
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    _scaffold(project, "DF-9001", candidate)
    assert "项目骨架已就绪" not in (project / "app.py").read_text(encoding="utf-8")
    assert "verify" in yaml.safe_load(old_workflow.read_text(encoding="utf-8"))["jobs"]
    preflight = old_workflow.read_text(encoding="utf-8")
    assert "Scorer contract" in preflight
    assert "keel gate" not in preflight
    assert "KEEL_CLIENT_PRIVATE_KEY" not in preflight
    assert "keel release" not in preflight
    assert "pytest-asyncio==1.4.0" in (project / "requirements.lock").read_text(encoding="utf-8")
    scorers = load_scorers(project / "evals/scorers.py")
    assert len(scorers) == 1
    score = scorers[0]
    assert score({"decision": "停在 H1 关口"}, '{"output":{"decision":"停在 H1 关口"}}', []) == 1.0
    assert score({"decision": "停在 H1 关口"}, '{"output":{"decision":"直接发布"}}', []) == 0.0
    assert score({"decision": "停在 H1 关口"}, "not-json", []) == 0.0
    assert score({"decision": "停在 H1 关口"}, '{"status":"BLOCKED"}', []) == 0.0
    _scaffold(project, "DF-9001", candidate)
    assert (project / "requirements.lock").read_text(encoding="utf-8").count("pytest-asyncio==1.4.0") == 1
    checked = subprocess.run([sys.executable, "-m", "pytest", "tests", "-q"],
                             cwd=project, capture_output=True, text=True)
    assert checked.returncode == 0, checked.stdout + checked.stderr


def test_generated_entry_accepts_visible_seed_text_and_json_object(tmp_path):
    project = tmp_path / "spec-agent"
    for name in ("tools", "tests", "evals"):
        (project / name).mkdir(parents=True, exist_ok=True)
    (project / "requirements.lock").write_text("pytest==9.1.1\n", encoding="utf-8")
    (project / "agent.yaml").write_text(yaml.safe_dump(manifest()), encoding="utf-8")
    (project / "spec.json").write_text("{}", encoding="utf-8")
    _scaffold(project, "DF-9001", {"files": [
        {"path": "role.py", "content": ROLE_CODE},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
        {"path": "tests/test_role.py", "content": TEST_CODE},
    ]})
    tree = ast.parse((project / "app.py").read_text(encoding="utf-8"))
    entry = next(node for node in tree.body if isinstance(node, ast.AsyncFunctionDef) and node.name == "run")
    entry.decorator_list = []
    captured = []

    async def execute(data, ctx):
        captured.append(data)
        return {"status": "READY"}

    class Ctx:
        def step(self, name):
            assert name == "role"

        def final(self, answer):
            return answer

    class Request:
        def __init__(self, value):
            self.input = {"text": value}

    class ErrorCode:
        SERVER_INVALID_PARAM = "SERVER_INVALID_PARAM"

    class KeelError(Exception):
        pass

    namespace = {"json": json, "execute": execute, "ErrorCode": ErrorCode, "KeelError": KeelError}
    exec(compile(ast.fix_missing_locations(ast.Module(body=[entry], type_ignores=[])), "app.py", "exec"), namespace)
    asyncio.run(namespace["run"](Request("请起草需求单"), Ctx()))
    asyncio.run(namespace["run"](Request('{"text":"请起草需求单","mode":"COLLAB"}'), Ctx()))
    asyncio.run(namespace["run"](Request('"请起草需求单"'), Ctx()))
    assert captured == [{"text": "请起草需求单"}, {"text": "请起草需求单", "mode": "COLLAB"},
                        {"text": '"请起草需求单"'}]
    import pytest
    with pytest.raises(KeelError):
        asyncio.run(namespace["run"](Request("[1,2]"), Ctx()))
