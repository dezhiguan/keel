"""Locked, checkpointed delivery of exact candidate commits."""
import fcntl
import httpx
import json
import logging
import os
from pathlib import Path
import re
import yaml
from keel.cli.new import create_from_spec, create_project
from keel.cli.repo import _files, _post
from keel.protocol.errors import ErrorCode, KeelError
from tools.manifest_check import check
from candidate import GENERAL_TEMPLATES, parse as parse_candidate, snapshot_errors
from roles import ROLES
from scaffolding import _scaffold, _readme_with_job_note

def _cached_delivery_candidate(work):
    """Reuse the persisted candidate when retrying this exact delivery attempt."""
    from tools.state import folder
    repair = work.get("repair") or {}
    job_id = work.get("jobId")
    if (not repair or not isinstance(job_id, str)
            or not re.fullmatch(r"DF-\d+", job_id)):
        return None
    checkpoint = folder(job_id) / "delivery.json"
    if not checkpoint.is_file():
        return None
    try:
        state = json.loads(checkpoint.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None
    if (state.get("attempt") != repair.get("attempt")
            or state.get("expectedSha") != repair.get("failedSha")
            or (state.get("push") is not None and not re.fullmatch(
                r"[0-9a-f]{40}", str(state["push"].get("sha") or "")))):
        return None
    candidate = state.get("candidate")
    if not isinstance(candidate, dict):
        return None
    requirement = work.get("requirement") or {}
    _, errors = parse_candidate(json.dumps(candidate), template=requirement.get("template"),
                                selected_tools=requirement.get("tools") or [])
    return candidate if not errors else None

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
    repair = work.get("repair") or {}
    attempt = int(repair.get("attempt") or 1)
    ordinary_limit = 5 + min(2, max(0, int(repair.get("templateRefreshes") or 0)))
    configured_limit = repair.get("maxAttempts")
    if configured_limit is not None and (type(configured_limit) is not int or not 1 <= configured_limit <= 35):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "沙箱修复窗口无效")
    if attempt < 1 or attempt > (configured_limit if configured_limit is not None else
                                 11 if repair.get("sdkRepair") is True else
                                 11 if repair.get("s4Repair") is True else
                                 10 if repair.get("ciRefresh") is True else
                                 8 if repair.get("reviewRepair") is True else
                                 7 if repair.get("infrastructureOnly") is True else ordinary_limit):
        raise KeelError(ErrorCode.SERVER_INVALID_PARAM,
                        f"沙箱修正轮次无效，含模板刷新最多 {ordinary_limit} 轮")
    if attempt > 1:
        failed_sha = repair.get("failedSha")
        fresh = not state
        current = attempt - 1 if fresh else int(state.get("attempt", 1))
        if (not isinstance(failed_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", failed_sha)
                or (fresh and snapshot_errors(repair.get("previousCandidate")))
                or (not fresh and attempt == current + 1 and state.get("push", {}).get("sha") != failed_sha)
                or (attempt == current and state.get("expectedSha") != failed_sha)
                or attempt not in (current, current + 1)):
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "沙箱修复提交与上一轮不一致")
        if current < attempt:
            candidate = work.get("candidate")
            candidate_errors = (snapshot_errors(candidate) if repair.get("infrastructureOnly")
                                else parse_candidate(json.dumps(candidate))[1])
            if candidate_errors:
                raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "修复后的岗位候选无效")
            state["candidate"] = candidate
            state["attempt"] = attempt
            state["expectedSha"] = failed_sha
            state.pop("push", None)
            state.pop("result", None)
            _save(checkpoint, state)
    if state.get("result"):
        return state["result"]
    functional = (requirement.get("template") in GENERAL_TEMPLATES
                  and (requirement["target_agent"] not in ROLES
                       or requirement["template"] == ROLES[requirement["target_agent"]][0]))
    if functional and not state.get("candidate"):
        candidate = work.get("candidate")
        if not isinstance(candidate, dict) or parse_candidate(json.dumps(candidate))[1]:
            raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "岗位代码候选未通过校验")
        state["candidate"] = candidate
        _save(checkpoint, state)
    draft = work["draft"]
    errors = check(draft["manifest"], requirement["target_agent"], None)
    if errors:
        raise KeelError(ErrorCode.AGENT_MANIFEST_INVALID, "；".join(errors[:5]))
    (root / "spec.json").write_text(json.dumps(draft["spec"], ensure_ascii=False, indent=2), encoding="utf-8")
    (root / "agent.yaml").write_text(yaml.safe_dump(draft["manifest"], allow_unicode=True, sort_keys=False), encoding="utf-8")
    project = root / requirement["target_agent"]
    if not project.exists():
        project = (create_project(requirement["target_agent"], template=requirement["template"], root=root)
                   if functional else create_from_spec(root / "spec.json", root=root))
    # H1 edits are preserved in the delivered spec even after a failed delivery attempt.
    (project / "spec.json").write_text((root / "spec.json").read_text(), encoding="utf-8")
    (project / "agent.yaml").write_text((root / "agent.yaml").read_text(), encoding="utf-8")
    _scaffold(project, job_id, state.get("candidate") if functional else None)
    if functional:
        visible = work.get("visibleSeeds") or []
        (project / "evals/seed.jsonl").write_text(
            "".join(json.dumps({"input": item["input"], "expected": item["expected"], "tags": item.get("tags") or []},
                               ensure_ascii=False) + "\n" for item in visible), encoding="utf-8")
    base = os.environ.get("KEEL_GIT_CI_URL", "").strip().rstrip("/")
    if not base or not os.environ.get("KEEL_GIT_CI_TOKEN"):
        raise KeelError(ErrorCode.GIT_UPSTREAM_FAILED, "Git 服务未配置")
    name = requirement["target_agent"]
    try:
        if not state.get("repo"):
            state["repo"] = _post(base, "git.repo.create", {"name": name, "jobId": job_id})
            _save(checkpoint, state)
        if not state.get("push"):
            push_body = {"repo": name, "branch": "draft", "jobId": job_id,
                "message": f"Keel {'build' if functional else 'scaffold'} {job_id}: {requirement['title']}",
                "files": _files(project)}
            if state.get("expectedSha"):
                push_body["expectedSha"] = state["expectedSha"]
            state["push"] = _post(base, "git.branch.push", push_body)
            _save(checkpoint, state)
        opened = _post(base, "git.pr.open", {"repo": name, "head": "draft", "base": "main", "jobId": job_id,
            "title": requirement["title"], "body": f"任务 {job_id}\n\n{requirement['goal']}\n\n"
                + ("模型生成岗位代码候选，测试、人工评审、门禁和发布尚未完成。" if functional
                   else "骨架交付，待人工开发、评测和发布。")})
        result = {**opened, "sha": state["push"]["sha"], "branch": "draft",
                  "runtimeAdapterVersion": "sandbox-checks-v3",
                  "checks": "ENABLED",
                  "capabilityStatus": "UNVERIFIED" if functional else "SCAFFOLD",
                  "gateStatus": "NOT_RUN",
                  "url": opened.get("url") or f"https://github.com/{opened['repo']}/pull/{opened['number']}"}
        if state["push"].get("recovered"):
            result.pop("runtimeAdapterVersion", None)
        state["result"] = result
        _save(checkpoint, state)
        return result
    except (ValueError, KeyError, httpx.HTTPError) as exc:
        logging.getLogger(__name__).warning("candidate delivery failed job=%s cause=%s detail=%s",
                                            job_id, type(exc).__name__, str(exc)[:240])
        raise KeelError(ErrorCode.GIT_UPSTREAM_FAILED, "骨架 PR 交付失败") from exc

def _save(path: Path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
    temporary.replace(path)
