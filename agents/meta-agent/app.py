"""meta-agent M1: requirement -> spec sheet + agent.yaml draft -> wait for a person to confirm or revise.

Spec: docs/specs/devflow/DF-5-meta-agent.md
"""

import json
from pathlib import Path

import yaml

from keel import Agent
from keel.protocol.errors import ErrorCode, KeelError

from tools import catalog as catalog_store
from tools import state as run_state
from tools.manifest_check import NAME, SELF, check

MAX_REVISIONS = 3
DRAFT_ATTEMPTS = 2
CONFIRM = {"确认", "confirm"}
RULES = """1. 只生成研发智能体（为 Keel 研发流程服务的智能体），kind 固定为 Agent。
2. metadata.name 小写字母开头，3～40 位，只含小写字母、数字和连字符；不能是 meta-agent。
3. risk 为 high 的工具必须写 approval: required。
4. 工具只能从目录里选；目录为空时只能用需求里列出的工具。风险等级不得低于目录登记值。
5. audit.captureFields 不得包含用户原文、手机号、身份证号等个人信息字段。
6. 不要写任何 API Key、密码；runtime.endpoint 由平台按约定填写，不要写。"""
ENDPOINT = "http://{name}.agents.svc.cluster.local:8000"

agent = Agent.from_manifest(Path(__file__).with_name("agent.yaml"))


@agent.tool(name="catalog.lookup")
def lookup_catalog(target_agent: str | None = None, version: int = 1) -> list[dict] | None:
    loaded = catalog_store.load()
    return None if loaded is None else list(loaded.values())


@agent.tool(name="manifest.validate")
def validate_manifest(manifest: dict, target_agent: str | None = None, version: int = 1) -> list[str]:
    return check(manifest, target_agent, catalog_store.load())


@agent.entry
async def run(request, ctx):
    if getattr(request, "resume", False):
        return await _resume(request, ctx)
    requirement = _requirement(request.input.get("text") or "")
    target = _target(requirement)
    ctx.step("intake")
    catalog = await ctx.tools.call("catalog.lookup", target_agent=target, version=1)
    prompt = await ctx.prompt("draft")
    content = prompt.compile(requirement=_dump(requirement), catalog=_dump(catalog or []), rules=RULES)
    draft = await _draft(ctx, prompt, [{"role": "user", "content": content}], target, 1)
    state = {"requirement": requirement, "target_agent": draft["manifest"]["metadata"]["name"],
             "version": 1, "revisions": 0, "catalog_checked": catalog is not None, **draft}
    run_state.save(ctx.run_id, state)
    return _ask(ctx, state)


async def _resume(request, ctx):
    state = run_state.load(ctx.run_id)
    if state is None:
        raise KeelError(ErrorCode.RUN_NOT_FOUND)
    reply = (request.input.get("text") or "").strip()
    if reply.lower() in CONFIRM:
        return _finish(ctx, state)
    if state["revisions"] >= MAX_REVISIONS:
        ctx.step("handover")
        return ctx.final(f"已改 {MAX_REVISIONS} 轮仍未确认，请人工编写岗位说明书或重新提交需求。\n\n"
                         f"最后一版：\n\n{_render(state)}")
    ctx.step("revise")
    version = state["version"] + 1
    catalog = await ctx.tools.call("catalog.lookup", target_agent=state["target_agent"], version=version)
    prompt = await ctx.prompt("revise")
    content = prompt.compile(requirement=_dump(state["requirement"]),
                             previous=_dump({"spec": state["spec"], "manifest": state["manifest"]}),
                             feedback=reply, catalog=_dump(catalog or []), rules=RULES)
    draft = await _draft(ctx, prompt, [{"role": "user", "content": content}], state["target_agent"], version)
    state.update(draft, version=version, revisions=state["revisions"] + 1, last_feedback=reply,
                 catalog_checked=catalog is not None)
    run_state.save(ctx.run_id, state)
    return _ask(ctx, state)


def _requirement(text: str) -> dict:
    text = text.strip()
    if not text:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "需求不能为空")
    try:
        data = json.loads(text)
    except json.JSONDecodeError:
        return {"goal": text}
    return data if isinstance(data, dict) else {"goal": text}


def _target(requirement: dict) -> str | None:
    # TODO(DF-1): use DEVFLOW_LINEAGE_FORBIDDEN for lineage refusals once it is registered.
    if requirement.get("layer", "dev") != "dev":
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "元智能体只生产研发智能体，业务智能体由研发流水线生产")
    if requirement.get("kind", "CREATE") != "CREATE":
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "改造已有智能体要读注册表里的现有 manifest，DF-5d 之后支持")
    target = requirement.get("target_agent")
    if target is None:
        return None
    if target == SELF:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "元智能体只能由人修改")
    if not isinstance(target, str) or not NAME.match(target):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "target_agent 不合规：小写字母开头，3～40 位，字母数字和连字符")
    return target


async def _draft(ctx, prompt, messages: list[dict], target: str | None, version: int) -> dict:
    errors: list[str] = []
    for _ in range(DRAFT_ATTEMPTS):
        reply = await ctx.llm.chat(messages, prompt=prompt)
        draft, errors = _parse(reply)
        if not errors:
            errors = await ctx.tools.call("manifest.validate", manifest=draft["manifest"],
                                          target_agent=target, version=version)
        if not errors:
            return draft
        messages = messages + [
            {"role": "assistant", "content": reply or ""},
            {"role": "user", "content": "上一版没有通过校验，请逐条修正后只返回 JSON：\n- " + "\n- ".join(errors[:10])},
        ]
    raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "岗位说明书两次未通过校验：" + "；".join(errors[:5]))


def _parse(reply: str | None) -> tuple[dict, list[str]]:
    text = (reply or "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return {}, ["没有返回 JSON 对象"]
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError as exc:
        return {}, [f"JSON 解析失败：{exc.msg}"]
    if not isinstance(data.get("spec"), dict) or not isinstance(data.get("manifest"), dict):
        return {}, ["必须同时包含 spec 和 manifest 两个对象"]
    manifest = data["manifest"]
    name = (manifest.get("metadata") or {}).get("name")
    if isinstance(name, str) and NAME.match(name) and isinstance(manifest.get("spec"), dict):
        runtime = manifest["spec"].setdefault("runtime", {})
        if isinstance(runtime, dict):
            runtime["endpoint"] = ENDPOINT.format(name=name)
    return {"spec": data["spec"], "manifest": manifest}, []


def _ask(ctx, state: dict):
    ctx.step("await_confirm")
    return ctx.suspend("input_required", ref=ctx.run_id,
                       prompt=f"第 {state['version']} 版已生成。回复「确认」结束，或直接写修改意见。\n\n{_render(state)}")


def _finish(ctx, state: dict):
    folder = run_state.folder(ctx.run_id)
    folder.mkdir(parents=True, exist_ok=True)
    (folder / "spec.json").write_text(_dump(state["spec"]), encoding="utf-8")
    (folder / "agent.yaml").write_text(_yaml(state["manifest"]), encoding="utf-8")
    ctx.step("confirmed")
    return ctx.final(f"{_render(state)}\n\n产物：`{folder}/spec.json`、`{folder}/agent.yaml`\n\n"
                     "后续步骤（DF-5b 以后）：生成项目骨架并在沙箱验证、建仓库开 PR、staging 门禁与发布申请。")


def _render(state: dict) -> str:
    spec = state["spec"]
    lines = [f"## 需求单 · {spec.get('title') or state['target_agent']}（第 {state['version']} 版）", ""]
    for key, label in (("goal", "目标"), ("users", "使用者与场景"), ("io", "输入与输出"), ("success", "成功标准")):
        lines.append(f"- **{label}**：{spec.get(key) or '待确认'}")
    for key, label in (("risks", "风险"), ("pending", "待确认")):
        if spec.get(key):
            lines.append(f"- **{label}**：" + "；".join(str(item) for item in spec[key]))
    if not state.get("catalog_checked"):
        lines.append("- **工具**：未核对注册表（工具目录未接入）")
    lines += ["", "## agent.yaml", "", "```yaml", _yaml(state["manifest"]).rstrip(), "```"]
    return "\n".join(lines)


def _dump(value) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2)


def _yaml(value) -> str:
    return yaml.safe_dump(value, allow_unicode=True, sort_keys=False)


app = agent.asgi()
