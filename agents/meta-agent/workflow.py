"""Stateless model turns for the server-owned workflow; only Git delivery keeps a local checkpoint."""

import fcntl
import json
import os
from pathlib import Path

import yaml

from keel.cli.new import create_from_spec
from keel.cli.repo import _files, _post
from keel.protocol.errors import ErrorCode, KeelError
from tools.manifest_check import check


async def execute(work, ctx, draft_model):
    from app import _target, _dump, RULES
    from opentelemetry import trace
    trace.get_current_span().set_attribute("keel.devflow.job_id", str(work.get("jobId") or ""))
    requirement = work.get("requirement") or {}
    target = _target(requirement)
    if not target or requirement.get("template") != "tool-agent":
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "最小闭环只支持 tool-agent 骨架")
    if work.get("action") == "DELIVER":
        result = await ctx.tools.call("scaffold.deliver", work=work, target_agent=target,
                                      version=int(work.get("version", 1)), job_id=work["jobId"])
        return ctx.final(_dump(result))
    if work.get("action") != "DRAFT":
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM)
    catalog = work.get("catalog") or []
    registered = {row["name"]: {**row, "risk": str(row.get("risk") or "LOW").lower()} for row in catalog}
    previous = work.get("draft")
    prompt = await ctx.prompt("revise" if previous else "draft")
    content = prompt.compile(requirement=_dump(requirement), catalog=_dump(list(registered.values())), rules=RULES,
                             previous=_dump(previous or {}), feedback=str(work.get("feedback") or ""))
    content += "\n只生成 Python tool-agent。spec 的 title/goal/users/io/success 必须为字符串，pending 必须为字符串数组。"
    for _ in range(2):
        drafted = await draft_model(ctx, prompt, [{"role": "user", "content": content}], target, int(work.get("version", 1)))
        manifest = drafted["manifest"]
        config = manifest["spec"]
        config["runtime"].update(type="code", language="python", liveness="k8s")
        config["models"] = {"default": "qwen-plus", "budget": {"dailyCny": requirement["dailyBudgetCny"]}}
        manifest["metadata"]["owner"] = requirement["owner"]
        # Resource selection and risk are authoritative input, never model-granted permissions.
        expected = set(requirement.get("tools") or [])
        actual = {t.get("name") for t in config.get("tools") or []}
        errors = check(manifest, target, registered)
        if actual != expected:
            errors.append("tools 必须与需求中选择的工具完全一致")
        if config.get("knowledge"):
            errors.append("最小闭环不支持知识库配置")
        if not errors:
            sheet = drafted["spec"]
            for key in ("title", "goal", "users", "io", "success"):
                if not isinstance(sheet.get(key), str) or not sheet[key].strip():
                    sheet[key] = str(requirement.get(key) or "待确认")
            if not isinstance(sheet.get("pending"), list):
                sheet["pending"] = []
            sheet["pending"] = [str(item) for item in sheet["pending"]]
            drafted["manifestYaml"] = yaml.safe_dump(manifest, allow_unicode=True, sort_keys=False)
            return ctx.final(_dump(drafted))
        content += "\n修正以下业务校验错误：" + "；".join(errors[:5])
    raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "；".join(errors[:5]))


def deliver(work):
    from tools.state import folder
    job_id = work.get("jobId")
    if not isinstance(job_id, str) or not job_id.startswith("DF-") or not job_id[3:].isdigit():
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM)
    root = folder(job_id)
    root.mkdir(parents=True, exist_ok=True)
    with (root / "delivery.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        return _deliver(work)


def _deliver(work):
    from tools.state import folder
    requirement = work["requirement"]
    job_id = work["jobId"]
    if not isinstance(job_id, str) or not job_id.startswith("DF-") or not job_id[3:].isdigit():
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM)
    root = folder(job_id)
    root.mkdir(parents=True, exist_ok=True)
    checkpoint = root / "delivery.json"
    state = json.loads(checkpoint.read_text()) if checkpoint.exists() else {}
    if state.get("result"):
        return state["result"]
    draft = work["draft"]
    errors = check(draft["manifest"], requirement["target_agent"], None)
    if errors:
        raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "；".join(errors[:5]))
    (root / "spec.json").write_text(json.dumps(draft["spec"], ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "agent.yaml").write_text(yaml.safe_dump(draft["manifest"], allow_unicode=True, sort_keys=False), encoding="utf-8")
    project = root / requirement["target_agent"]
    if not project.exists():
        project = create_from_spec(root / "spec.json", root=root)
    # H1 edits are preserved in the delivered spec even after a failed delivery attempt.
    (project / "spec.json").write_text((root / "spec.json").read_text(), encoding="utf-8")
    (project / "agent.yaml").write_text((root / "agent.yaml").read_text(), encoding="utf-8")
    base = os.environ.get("KEEL_GIT_CI_URL", "").strip().rstrip("/")
    if not base or not os.environ.get("KEEL_GIT_CI_TOKEN"):
        raise KeelError(ErrorCode.GIT_UPSTREAM_FAILED, "Git 服务未配置")
    name = requirement["target_agent"]
    try:
        if not state.get("repo"):
            state["repo"] = _post(base, "git.repo.create", {"name": name, "jobId": job_id})
            _save(checkpoint, state)
        if not state.get("push"):
            state["push"] = _post(base, "git.branch.push", {"repo": name, "branch": "draft", "jobId": job_id,
                "message": f"Keel scaffold {job_id}: {requirement['title']}", "files": _files(project)})
            _save(checkpoint, state)
        opened = _post(base, "git.pr.open", {"repo": name, "head": "draft", "base": "main", "jobId": job_id,
            "title": requirement["title"], "body": f"任务 {job_id}\n\n{requirement['goal']}\n\n骨架交付，待人工开发、评测和发布。"})
        result = {**opened, "sha": state["push"]["sha"], "branch": "draft",
                  "url": opened.get("url") or f"https://github.com/{opened['repo']}/pull/{opened['number']}"}
        state["result"] = result
        _save(checkpoint, state)
        return result
    except (ValueError, KeyError) as exc:
        raise KeelError(ErrorCode.GIT_UPSTREAM_FAILED, "骨架 PR 交付失败") from exc


def _save(path: Path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)
