import asyncio
import ast
import json
import subprocess
import sys

import pytest
import yaml

from candidate import generate, parse, snapshot_errors
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
        self.prompts.append(messages)
        return next(self.replies)


def test_meta_agent_generates_role_code_from_confirmed_spec():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    llm = FakeModel([json.dumps(candidate)])
    ctx = type("Ctx", (), {"llm": llm})()
    work = {"requirement": {"target_agent": "spec-agent", "template": "graph-agent", "tools": []},
            "draft": {"spec": {"goal": "把真实需求变成结构化需求单"},
                      "manifest": {"spec": {"tools": []}}}}
    output = asyncio.run(generate(work, ctx))
    assert output == candidate
    assert "把真实需求变成结构化需求单" in llm.prompts[0][0]["content"]
    assert "spec-agent" in llm.prompts[0][0]["content"]


def test_candidate_rejects_dictionary_access_on_sdk_objects_and_string_reply():
    role = ROLE_CODE.replace(
        'reply = await ctx.llm.chat([{"role": "user", "content": prompt.compile(input=requirement)}], prompt=prompt)',
        'tools_spec = ctx.manifest.spec.tools or []\n    for tool in tools_spec:\n        tool.get("name")\n'
        '    reply = await ctx.llm.chat([{"role": "user", "content": prompt.compile(input=requirement)}], prompt=prompt)\n'
        '    reply.get("content")')
    candidate = {"files": [{"path": "role.py", "content": role},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    errors = parse(json.dumps(candidate))[1]
    assert any("tools 条目是对象" in error for error in errors)
    assert any("chat 未传 tools 时返回字符串" in error for error in errors)
    candidate["files"][0]["content"] = role.replace('tool.get("name")', "tool.name").replace(
        'reply.get("content")', "reply")
    assert parse(json.dumps(candidate))[1] == []


def test_candidate_rejects_dictionary_llm_test_double():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE + '''
from unittest.mock import AsyncMock

def test_wrong_llm_double():
    class Holder: pass
    self = Holder()
    self.llm = Holder()
    self.llm.chat = AsyncMock(return_value={"content": "{}"})
'''}]}
    assert any("测试替身必须返回字符串" in error for error in parse(json.dumps(candidate))[1])


def test_candidate_rejects_all_paths_blocked_after_h1():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE.replace(
        'return {"status": "READY", "spec": json.loads(reply)}',
        'return {"status": "BLOCKED", "spec": json.loads(reply)}')},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
        {"path": "tests/test_role.py", "content": TEST_CODE}]}
    assert any("正常路径不能全部固定返回 BLOCKED" in error
               for error in parse(json.dumps(candidate))[1])


def test_meta_agent_generates_general_agent_from_confirmed_goal():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    llm = FakeModel([json.dumps(candidate)])
    ctx = type("Ctx", (), {"llm": llm})()
    work = {"requirement": {"target_agent": "qa-incident-helper", "template": "tool-agent",
                            "goal": "分析故障并调用日志工具", "tools": ["ci.log.fetch"]},
            "draft": {"spec": {"goal": "按证据给出故障原因"},
                      "manifest": {"spec": {"tools": [
                          {"name": "ci.log.fetch", "access": "read", "risk": "low", "approval": "none"}]}}}}
    assert asyncio.run(generate(work, ctx)) == candidate
    assert "qa-incident-helper" in llm.prompts[0][0]["content"]
    assert "分析故障并调用日志工具" in llm.prompts[0][0]["content"]


def test_generation_retry_includes_rejected_candidate_and_specific_errors():
    bad = {"files": [{"path": "role.py", "content": ROLE_CODE.replace('import json', 'import subprocess')},
                     {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                     {"path": "tests/test_role.py", "content": TEST_CODE}]}
    bad["files"][0]["content"] += "\n#" + "x" * 30_000
    bad["files"][1]["content"] += "\n" * 39_000
    bad["files"][2]["content"] += "\n#" + "x" * 15_000
    assert len(json.dumps(bad)) > 120_000
    fixed = {"files": [{"path": "role.py", "content": ROLE_CODE},
                       {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                       {"path": "tests/test_role.py", "content": TEST_CODE}]}
    model = FakeModel([json.dumps(bad), json.dumps(fixed)])
    work = {"requirement": {"target_agent": "spec-agent", "template": "graph-agent", "tools": []},
            "draft": {"spec": {"goal": "写需求单"}, "manifest": {"spec": {"tools": []}}}}
    assert asyncio.run(generate(work, type("Ctx", (), {"llm": model})())) == fixed
    assert len(model.prompts[1]) == 3
    assert model.prompts[1][1] == {"role": "assistant", "content": json.dumps(bad)}
    assert "未经允许的模块" in model.prompts[1][2]["content"]


def test_supervisor_candidate_requires_real_delegation_call():
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    _, errors = parse(json.dumps(candidate), template="supervisor")
    assert any("ctx.delegate" in error for error in errors)


def test_candidate_checks_keel_sdk_call_signatures_and_multiple_tools():
    role = '''async def execute(data, ctx):
    prompt = await ctx.prompt("role")
    await ctx.tools.call("ci.log.fetch", {"run_id": 1})
    await ctx.llm.chat([], temperature=0)
    return {"decision": "BLOCKED"}
'''
    candidate = {"files": [{"path": "role.py", "content": role},
                           {"path": "prompts/role.md", "content": "任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    _, errors = parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])
    assert any("ctx.tools.call" in error for error in errors)
    assert any("ctx.llm.chat" in error for error in errors)
    assert any("至少两个" in error for error in errors)


def test_multi_tool_candidate_must_show_evidence_to_model_and_preserve_blocked():
    role = '''import re
async def execute(data, ctx):
    log = await ctx.tools.call("ci.log.fetch", repo="sample", runId=1)
    diff = await ctx.tools.call("git.pr.diff", repo="sample", number=1)
    prompt = await ctx.prompt("role")
    message = prompt.compile(input=data, evidence={"log": log, "diff": diff})
    llm_response = await ctx.llm.chat([{"role": "user", "content": message}])
    if re.search(r"\\bPASS\\b", llm_response):
        return {"decision": "PASS"}
    return {"decision": "BLOCKED"}
'''
    candidate = {"files": [{"path": "role.py", "content": role},
                           {"path": "prompts/role.md", "content": "任务：{{input}}\n证据：{{evidence}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    _, errors = parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])
    assert any("扫描模型全文" in error for error in errors)
    assert any("prompt=prompt" in error for error in errors)
    candidate["files"][1]["content"] = "任务：{{input}}"
    _, errors = parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])
    assert any("{{evidence}}" in error for error in errors)
    candidate["files"][0]["content"] = role.replace(', evidence={"log": log, "diff": diff}', '')
    _, errors = parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])
    assert any("prompt.compile" in error and "evidence" in error for error in errors)


def test_multi_tool_prompt_accepts_sdk_whitespace_and_prompt_alias():
    role = '''async def execute(data, ctx):
    log = await ctx.tools.call("ci.log.fetch", repo="sample", runId="1")
    diff = await ctx.tools.call("git.pr.diff", repo="sample", number=1)
    prompt_template = await ctx.prompt("role")
    message = prompt_template.compile(input=data, evidence={"log": log, "diff": diff})
    await ctx.llm.chat([{"role": "user", "content": message}], prompt=prompt_template)
    return {"decision": "BLOCKED"}
'''
    candidate = {"files": [{"path": "role.py", "content": role},
                           {"path": "prompts/role.md", "content": "输入：{{ input }}\n证据：{{ evidence }}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    assert parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])[1] == []
    candidate["files"][0]["content"] = role.replace("prompt_template =", "prompt_template: object =")
    assert parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])[1] == []
    candidate["files"][1]["content"] += "\n风险：{{ tool.risk }}"
    assert any("无法替换的占位符" in error for error in
               parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])[1])
    candidate["files"][1]["content"] = "输入：{{ input }}\n证据：{{ evidence }}\n风险：{{ tool.risk"
    assert any("无法替换的占位符" in error for error in
               parse(json.dumps(candidate), selected_tools=["ci.log.fetch", "git.pr.diff"])[1])


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


def test_previous_snapshot_accepts_old_sdk_calls_for_repair():
    previous = {"files": [
        {"path": "role.py", "content": "async def execute(data, ctx):\n    return await ctx.tools.call('ci.log.fetch', {'run_id': 1})\n"},
        {"path": "prompts/role.md", "content": "旧提示词"},
        {"path": "tests/test_role.py", "content": "def test_old():\n    assert True\n"},
    ]}
    assert snapshot_errors(previous) == []
    assert parse(json.dumps(previous))[1]
    assert snapshot_errors({"files": previous["files"][:2]})


def test_generated_tests_use_synchronous_prompt_compile_mock():
    bad = {"files": [
        {"path": "role.py", "content": ROLE_CODE},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
        {"path": "tests/test_role.py", "content": """from unittest.mock import AsyncMock, MagicMock
def test_normal():
    prompt = MagicMock(compile=AsyncMock(return_value='x'))
    assert prompt
def test_rejection():
    assert True
"""},
    ]}
    assert any("prompt.compile 是同步方法" in error for error in parse(json.dumps(bad))[1])


def test_generated_role_rejects_double_escaped_regex_classes():
    bad_role = """import re
async def execute(data, ctx):
    prompt = await ctx.prompt('role')
    prompt.compile(input=data)
    return {'match': re.search(r'DF-\\\\d+', data.get('text', ''))}
"""
    bad = {"files": [
        {"path": "role.py", "content": bad_role},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
        {"path": "tests/test_role.py", "content": TEST_CODE},
    ]}
    assert any("双反斜杠" in error for error in parse(json.dumps(bad))[1])


def test_generated_tool_call_requires_actual_runtime_keywords():
    bad_role = """async def execute(data, ctx):
    prompt = await ctx.prompt('role')
    prompt.compile(input=data)
    await ctx.tools.call('ci.log.fetch', run_id='42')
    return {'decision': 'BLOCKED'}
"""
    bad = {"files": [
        {"path": "role.py", "content": bad_role},
        {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
        {"path": "tests/test_role.py", "content": TEST_CODE},
    ]}
    errors = parse(json.dumps(bad))[1]
    assert any("ci.log.fetch 缺少必需关键字参数" in error and "runId" in error for error in errors)


def test_candidate_rejects_conflicting_prompt_output_contract():
    candidate = {"files": [
        {"path": "role.py", "content": ROLE_CODE},
        {"path": "prompts/role.md", "content": "输出必须包含证据与 decision；顶层仅含 decision。{{input}}"},
        {"path": "tests/test_role.py", "content": TEST_CODE},
    ]}
    assert "岗位提示词对输出字段的要求互相冲突" in parse(json.dumps(candidate))[1]


def test_supervisor_scaffold_registers_manifest_children(tmp_path):
    project = tmp_path / "supervisor-agent"
    for name in ("tools", "tests", "evals"):
        (project / name).mkdir(parents=True, exist_ok=True)
    (project / "requirements.lock").write_text("pytest==9.1.1\n", encoding="utf-8")
    configured = manifest()
    configured["metadata"]["name"] = "supervisor-agent"
    configured["spec"]["delegates"] = ["child-a", "child-b"]
    (project / "agent.yaml").write_text(yaml.safe_dump(configured, sort_keys=False), encoding="utf-8")
    candidate = {"files": [{"path": "role.py", "content": ROLE_CODE},
                           {"path": "prompts/role.md", "content": "岗位任务：{{input}}"},
                           {"path": "tests/test_role.py", "content": TEST_CODE}]}
    _scaffold(project, "DF-9002", candidate)
    source = (project / "app.py").read_text(encoding="utf-8")
    ast.parse(source)
    assert "test_child_blocked_remains_blocked" in (project / "tests/test_platform_contract.py").read_text()
    assert "for _child_name in agent.manifest.spec.delegates" in source
    assert "agent.child(_child_name)" in source
    assert '"event: final"' in source


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
    assert "manifest.metadata.name == 'spec-agent'" in (project / "tests/test_smoke.py").read_text(encoding="utf-8")
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


def test_repair_patch_preserves_unchanged_files_and_requires_unique_anchors():
    from candidate import expand_repair_edits
    previous = {"files": [{"path": "role.py", "content": "first\nsecond\n"},
                           {"path": "prompts/role.md", "content": "prompt"},
                           {"path": "tests/test_role.py", "content": "tests"}]}
    raw = json.dumps({"edits": [{"path": "role.py", "old": "second", "new": "fixed"}]})
    result = {item["path"]: item["content"] for item in json.loads(expand_repair_edits(raw, previous))["files"]}
    assert result == {"role.py": "first\nfixed\n", "prompts/role.md": "prompt", "tests/test_role.py": "tests"}
    for edit in [{"path": "../app.py", "old": "first", "new": "bad"},
                 {"path": "role.py", "old": "missing", "new": "bad"},
                 {"path": "role.py", "old": "\n", "new": "bad"}]:
        with pytest.raises(ValueError):
            expand_repair_edits(json.dumps({"edits": [edit]}), previous)
