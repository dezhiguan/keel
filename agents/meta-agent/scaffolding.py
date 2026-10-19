"""Independent project adapters, prompt files and CI scaffolding."""
import json
import re
import shutil
from pathlib import Path
import yaml
from keel.protocol.errors import ErrorCode, KeelError

def _readme_with_job_note(existing: str, job_id: str) -> str:
    """Keep one delivery note. Repeated repairs must not append the same section again."""
    note = (
        f"## 生产任务 {job_id}\n\n"
        "模型生成的研发岗位代码候选，需通过隔离沙箱、人工 PR review、评测门禁和发布审批。\n"
        "多智能体项目运行时需配置 KEEL_DELEGATE_ROUTES，按 agent.yaml 中的子智能体名称映射其登记 endpoint 和 env。"
        "例如 `{\"child-name\":{\"endpoint\":\"http://child-service:8000\",\"env\":\"dev\"}}`。"
        "Keel 可信流水线会自动注入；独立部署时由开发者配置，缺少绑定会明确失败。\n"
    )
    stripped = re.sub(
        rf"(?:\n*## 生产任务 {re.escape(job_id)}\n\n"
        r"模型生成的研发岗位代码候选[\s\S]*?缺少绑定会明确失败。\n?)+",
        "",
        existing,
    )
    return stripped.rstrip() + "\n\n" + note

SUPERVISOR_CHILD_WIRING = '''import os
import httpx
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator


async def _invoke_child(name: str, text: str, budget: dict, trace_id: str):
    route = json.loads(os.environ.get("KEEL_DELEGATE_ROUTES", "{}" )).get(name)
    if not isinstance(route, dict) or route.get("env") not in {"dev", "test", "staging", "prod"}:
        raise RuntimeError(f"委派 {name} 缺少明确的环境路由，请配置 KEEL_DELEGATE_ROUTES")
    endpoint = str(route.get("endpoint") or "").rstrip("/") + "/v1/invoke"
    if not endpoint.startswith(("http://", "https://")):
        raise RuntimeError(f"委派 {name} 缺少登记 endpoint")
    headers = {"X-Keel-Agent": name, "X-Keel-Env": route["env"]}
    TraceContextTextMapPropagator().inject(headers)
    async with httpx.AsyncClient(timeout=180) as client:
        response = await client.post(endpoint, json={"input": {"text": text}}, headers=headers)
        response.raise_for_status()
    for block in response.text.split("\\n\\n"):
        if "event: final" not in block:
            continue
        for line in block.splitlines():
            if not line.startswith("data:"):
                continue
            answer = json.loads(line[5:].strip()).get("answer")
            if not isinstance(answer, str) or not answer.strip():
                raise RuntimeError(f"委派 {name} 返回空结果")
            try:
                return json.loads(answer)
            except json.JSONDecodeError:
                return answer
    raise RuntimeError(f"委派 {name} 未返回 final")


for _child_name in agent.manifest.spec.delegates or []:
    agent.child(_child_name)(lambda text, budget, trace_id, name=_child_name:
                             _invoke_child(name, text, budget, trace_id))


'''

def _scaffold(project: Path, job_id: str, candidate: dict | None = None):
    """Strip unrelated work-order examples; CI verifies this actual manifest and Python syntax."""
    # Older delivery directories predate the SDK release pin. Their CI already
    # reads this file, so restore it before collecting files for a new commit.
    sdk_version = project / ".keel-sdk-version"
    if not sdk_version.exists():
        sdk_version.write_text("1.0.0\n", encoding="utf-8")
    (project / "tools/checkpoint.py").unlink(missing_ok=True)
    (project / "evals/seed.jsonl").write_text("", encoding="utf-8")
    (project / "evals/scorers.py").write_text("# Add scorers after humans provide evaluation cases.\n", encoding="utf-8")
    if candidate is not None:
        (project / "AGENTS.md").write_text(
            f"# {project.name} 开发说明\n\n"
            "这是一个独立智能体项目。先阅读 README.md、agent.yaml、提示词和评测样例。\n"
            "app.py 是协议入口与适配层；role.py 是岗位业务模块，execute(data, ctx) 执行业务逻辑。\n"
            "辅助业务模块可以按需放在 tools/，不要求把 role.py 的逻辑搬到入口或 tools/。\n"
            "行为改动同步维护 prompts/、tests/ 和可见评测，提交前运行项目测试。\n"
            "平台运行时通过版本化 keel-sdk 引入，不把底座源码、契约或密钥复制进项目。\n",
            encoding="utf-8")
        (project / "evals/scorers.py").write_text('''import json
import re
import unicodedata


def _normalized(value):
    return re.sub(r"\\s+", "", unicodedata.normalize("NFKC", value))


def score_decision(expected, answer, tags):
    if not isinstance(expected, dict) or not isinstance(expected.get("decision"), str):
        return 0.0
    try:
        result = json.loads(answer)
    except (TypeError, ValueError):
        return 0.0
    if not isinstance(result, dict) or not isinstance(result.get("output"), dict):
        return 0.0
    actual = result["output"].get("decision")
    if not isinstance(actual, str) or not actual.strip():
        return 0.0
    return 1.0 if _normalized(actual) == _normalized(expected["decision"]) else 0.0
''', encoding="utf-8")
        template = Path(__file__).with_name("pre-review-workflow.yml")
        if not template.is_file():
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "岗位 CI 工作流模板缺失")
        workflow = project / ".github/workflows/keel.yml"
        workflow.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(template, workflow)
        lock = project / "requirements.lock"
        dependencies = lock.read_text(encoding="utf-8")
        if "pytest-asyncio==" not in dependencies:
            lock.write_text(dependencies.rstrip() + "\npytest-asyncio==1.4.0\n", encoding="utf-8")
        for folder_name in ("tools", "tests", "prompts"):
            folder = project / folder_name
            if folder.is_dir():
                shutil.rmtree(folder)
        (project / "app.py").write_text('''import json
from keel import Agent
from keel.protocol.errors import ErrorCode, KeelError
from role import execute

agent = Agent.from_manifest("agent.yaml")

@agent.entry
async def run(request, ctx):
    raw = request.input.get("text")
    if not isinstance(raw, str):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "输入 text 必须是字符串")
    try:
        data = json.loads(raw)
    except json.JSONDecodeError:
        data = {"text": raw}
    if isinstance(data, list):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "输入必须是 JSON 对象")
    if not isinstance(data, dict):
        data = {"text": raw}
    ctx.step("role")
    result = await execute(data, ctx)
    if not isinstance(result, dict):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "岗位结果必须是 JSON 对象")
    return ctx.final(json.dumps(result, ensure_ascii=False))

app = agent.asgi()
''', encoding="utf-8")
        manifest = yaml.safe_load((project / "agent.yaml").read_text(encoding="utf-8"))
        if (manifest.get("spec") or {}).get("delegates"):
            app_file = project / "app.py"
            source = app_file.read_text(encoding="utf-8")
            app_file.write_text(source.replace("@agent.entry\n", SUPERVISOR_CHILD_WIRING + "@agent.entry\n", 1),
                                encoding="utf-8")
        for item in candidate["files"]:
            output = project / item["path"]
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_text(item["content"], encoding="utf-8")
        # Trusted checks belong to the sandbox image, never the independent project.
        (project / "tests/test_platform_contract.py").unlink(missing_ok=True)
        readme = project / "README.md"
        existing = readme.read_text(encoding="utf-8") if readme.is_file() else f"# {project.name}\n"
        readme.write_text(_readme_with_job_note(existing, job_id), encoding="utf-8")
    else:
        spec = json.loads((project / "spec.json").read_text(encoding="utf-8"))
        write_prompt_scaffold(project, spec)
    (project / "tests/test_smoke.py").write_text(f'''import ast
import json
from pathlib import Path
import yaml
from keel.manifest import load_manifest

ROOT = Path(__file__).resolve().parents[1]

def test_manifest_matches_the_delivered_contract():
    manifest = load_manifest(ROOT / "agent.yaml")
    assert manifest.metadata.name == {project.name!r}

def test_entry_is_valid_python_and_has_an_async_entry():
    tree = ast.parse((ROOT / "app.py").read_text())
    assert any(isinstance(node, ast.AsyncFunctionDef) and node.name == "run" for node in tree.body)
''', encoding="utf-8")


    if candidate is not None:
        return
    readme = project / "README.md"
    existing = readme.read_text(encoding="utf-8") if readme.is_file() else f"# {project.name}\n"
    readme.write_text(existing.rstrip() + f'''

## 研发任务 {job_id}

此 PR 交付已确认的需求单、agent.yaml 和 Python 项目骨架，业务逻辑尚未实现。
需求的使用者、输入输出和成功标准以 spec.json 为准。

当前 app.py 从 ctx.prompt 取确认需求生成的初版提示词；请按需求实现实际岗位逻辑。模型调用使用 ctx.llm，工具调用使用 ctx.tools.call。
manifest 中选择的共享工具尚需平台授权和调用适配。运行配置由平台 Secret 注入，不在项目中存放密钥。

`.github/workflows/keel.yml` 会在首次推送时触发。CI 触发不等于业务评测通过。seed.jsonl 为空，待人提供真实用例。
本地开发安装已发布的 keel-sdk 后使用 keel dev。
业务测试和评测通过后，按平台流程构建运行镜像、登记、自检和申请发布。
''', encoding="utf-8")

def write_prompt_scaffold(project: Path, spec: dict) -> None:
    """Keep the confirmed prompt beside source so register can publish its first version."""
    prompt_file = project / "prompts/role.md"
    prompt_file.parent.mkdir(parents=True, exist_ok=True)
    prompt_file.write_text(
        "岗位目标：" + str(spec.get("goal") or "按已确认需求完成任务") + "\n"
        "成功标准：" + str(spec.get("success") or "按需求验收") + "\n"
        "输入：{{input}}\n请根据输入和授权能力完成岗位任务。\n", encoding="utf-8")
    (project / "app.py").write_text('''from keel import Agent

agent = Agent.from_manifest("agent.yaml")

@agent.entry
async def run(request, ctx):
    prompt = await ctx.prompt("role")
    text = prompt.compile(input=request.input.get("text", ""))
    answer = await ctx.llm.chat([{"role": "user", "content": text}], prompt=prompt)
    return ctx.final(answer)

app = agent.asgi()
''', encoding="utf-8")
