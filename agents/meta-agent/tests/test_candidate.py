import asyncio
import json
import subprocess
import sys

import yaml

from candidate import generate, parse
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


def test_generated_code_rejects_forbidden_imports_and_missing_behavior_tests():
    bad = {"files": [{"path": "role.py", "content": "import subprocess\nasync def execute(data, ctx): return {}"},
                     {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                     {"path": "tests/test_role.py", "content": "def test_only_one(): pass"}]}
    _, errors = parse(json.dumps(bad))
    assert any("导入" in error for error in errors)
    assert any("正常和拒绝" in error for error in errors)


def test_model_authored_role_runs_in_generated_project(tmp_path):
    project = tmp_path / "spec-agent"
    for name in ("tools", "tests", "evals"):
        (project / name).mkdir(parents=True, exist_ok=True)
    configured = manifest()
    configured["spec"]["runtime"]["endpoint"] = "http://spec-agent.agents.svc.cluster.local:8000"
    (project / "agent.yaml").write_text(yaml.safe_dump(configured, sort_keys=False), encoding="utf-8")
    (project / "spec.json").write_text(json.dumps({"title": "需求分析师"}), encoding="utf-8")
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    _scaffold(project, "DF-9001", candidate)
    assert "项目骨架已就绪" not in (project / "app.py").read_text(encoding="utf-8")
    checked = subprocess.run([sys.executable, "-m", "pytest", "tests", "-q"],
                             cwd=project, capture_output=True, text=True)
    assert checked.returncode == 0, checked.stdout + checked.stderr
