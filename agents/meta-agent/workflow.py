"""Stateless model-step orchestration; the platform owns production stage transitions."""
import json
import yaml
from keel.protocol.errors import ErrorCode, KeelError
from tools.manifest_check import check
from candidate import GENERAL_TEMPLATES, generate as generate_candidate, snapshot_errors
from evalset import expand as expand_evalset
from roles import ROLES
from model_steps import project_guidance
from review import review_candidate
from delivery import deliver, _deliver, _cached_delivery_candidate, _readme_with_job_note
from scaffolding import _scaffold, write_prompt_scaffold, SUPERVISOR_CHILD_WIRING

async def execute(work, ctx):
    from intake import _target, _dump, RULES
    from opentelemetry import trace
    trace.get_current_span().set_attribute("keel.devflow.job_id", str(work.get("jobId") or ""))
    requirement = work.get("requirement") or {}
    target = _target(requirement)
    template = requirement.get("template")
    if not target or template not in GENERAL_TEMPLATES:
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "研发智能体模板与目标不匹配")
    if work.get("action") == "GENERATE":
        if not getattr(ctx, "eval_run_id", None):
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "候选预览只供隔离评测，不执行交付")
        candidate = await generate_candidate(work, ctx)
        skill_version = getattr(ctx, "project_skill_version", None)
        return ctx.final(_dump({"candidate": candidate, "skillVersion": skill_version}))
    if work.get("action") == "DELIVER":
        if target not in ROLES or template == ROLES[target][0]:
            repair = work.get("repair") or {}
            if repair.get("infrastructureOnly"):
                candidate = repair.get("previousCandidate") or {}
                if snapshot_errors(candidate):
                    raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "原候选岗位代码已失效")
            else:
                candidate = _cached_delivery_candidate(work) or await generate_candidate(work, ctx)
            work = {**work, "candidate": candidate}
        result = await ctx.tools.call("scaffold.deliver", work=work, target_agent=target,
                                      version=int(work.get("version", 1)), job_id=work["jobId"])
        return ctx.final(_dump(result))
    if work.get("action") == "EVAL":
        return ctx.final(_dump(await expand_evalset(work, ctx)))
    if work.get("action") == "REVIEW":
        return ctx.final(_dump(await review_candidate(work, ctx)))
    if work.get("action") != "DRAFT":
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM)
    catalog = work.get("catalog") or []
    registered = {row["name"]: {**row, "risk": str(row.get("risk") or "LOW").lower()} for row in catalog}
    selected = requirement.get("tools") or []
    if (not isinstance(selected, list)
            or any(not isinstance(name, str) or name not in registered for name in selected)
            or len(selected) != len(set(selected))):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "选中的工具不在可用目录中")
    tools = []
    for name in selected:
        row = registered[name]
        access = str(row.get("access") or "read").lower()
        risk = row["risk"]
        if access not in ("read", "write", "exec") or risk not in ("low", "mid", "high"):
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, f"工具 {name} 的登记信息无效")
        tools.append({"name": name, "access": access, "risk": risk,
                      "approval": "required" if risk == "high" else "none"})
    previous = work.get("draft")
    prompt = await ctx.prompt("revise" if previous else "draft")
    content = prompt.compile(requirement=_dump(requirement), catalog=_dump(list(registered.values())), rules=RULES,
                             previous=_dump(previous or {}), feedback=str(work.get("feedback") or ""))
    content += (f"\n只生成 Python {template}。目标智能体只使用需求里明确选择的工具；"
                "Git PR 由平台交付，不是目标智能体的默认能力。"
                "spec 的 title/goal/users/io/success 必须为字符串，pending 必须为字符串数组。")
    content += await project_guidance(ctx)
    errors = []
    for _ in range(2):
        reply = await ctx.llm.chat([{"role": "user", "content": content}], prompt=prompt)
        sheet, parse_errors = _parse_sheet(reply)
        if parse_errors:
            errors = parse_errors
            content += "\n上一版没有得到结构化需求单，请只返回 JSON：" + "；".join(parse_errors)
            continue
        # Runtime, identity and permissions come from confirmed input and the tool registry.
        manifest = {"apiVersion": "keel/v1", "kind": "Agent",
                    "metadata": {"name": target, "displayName": requirement["title"], "owner": requirement["owner"]},
                    "spec": {"runtime": {"type": "code", "language": "python", "liveness": "k8s",
                                         "endpoint": f"http://{target}.agents.svc.cluster.local:8000"},
                             "kind": "workflow",
                             "auth": {"audience": target},
                             "models": {"default": ctx.manifest.spec.models.default_, "budget": {"dailyCny": requirement["dailyBudgetCny"]}},
                             "tools": tools, "knowledge": [],
                             "delegates": requirement.get("delegates") or [],
                             "guardrails": {"inputInjection": "enforce", "grounding": "off", "pii": "mask"},
                             "prompts": {"source": "langfuse", "items": [{"name": "role", "type": "text"}]},
                             "audit": {"captureFields": ["request_id"]},
                             "eval": {"dataset": f"{target}/seed"}}}
        errors = check(manifest, target, registered)
        if not errors:
            drafted = {"spec": sheet, "manifest": manifest}
            for key in ("title", "goal", "users", "io", "success"):
                sheet[key] = str(requirement.get(key) or "待确认")
            for key in ("risks", "pending"):
                sheet[key] = [str(item) for item in sheet.get(key, [])] if isinstance(sheet.get(key), list) else []
            drafted["manifestYaml"] = yaml.safe_dump(manifest, allow_unicode=True, sort_keys=False)
            return ctx.final(_dump(drafted))
        content += "\n修正以下业务校验错误：" + "；".join(errors[:5])
    raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "；".join(errors[:5]))

def _parse_sheet(reply):
    text = str(reply or "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start < 0 or end <= start:
        return {}, ["没有返回 JSON 对象"]
    try:
        data = json.loads(text[start:end + 1])
    except json.JSONDecodeError:
        return {}, ["JSON 解析失败"]
    if not isinstance(data, dict) or not isinstance(data.get("spec"), dict):
        return {}, ["必须包含 spec 对象"]
    return data["spec"], []
