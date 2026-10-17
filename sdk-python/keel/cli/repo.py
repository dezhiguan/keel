"""Open a draft pull request for a generated project. The token stays in the Git service."""

import os
import time
from pathlib import Path

import httpx

from keel.manifest import load_manifest

SKIPPED = {".git", ".keel", "__pycache__", ".pytest_cache"}
BRANCH = "draft"


def open_draft(project: str | Path, *, title: str, body: str) -> dict | None:
    base = os.environ.get("KEEL_GIT_CI_URL", "").strip().rstrip("/")
    if not base:
        return None
    root = Path(project)
    name = load_manifest(root / "agent.yaml").metadata.name
    message = title.strip() or name
    _post(base, "git.repo.create", {"name": name})
    _post(base, "git.branch.push", {
        "repo": name, "branch": BRANCH, "message": message, "files": _files(root),
    })
    return _post(base, "git.pr.open", {
        "repo": name, "head": BRANCH, "base": "main", "title": message, "body": body.strip() or message,
    })


def _files(root: Path) -> list[dict]:
    found = []
    for path in sorted(root.rglob("*")):
        relative = path.relative_to(root).as_posix()
        if not path.is_file() or SKIPPED.intersection(path.relative_to(root).parts):
            continue
        if ".." in relative.split("/"):
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except UnicodeDecodeError:
            continue
        found.append({"path": relative, "content": content})
    if not found:
        raise ValueError("骨架里没有可推送的文件")
    return found


def _post(base: str, tool: str, body: dict) -> dict:
    token = os.environ.get("KEEL_GIT_CI_TOKEN", "").strip()
    headers = {"Authorization": "Bearer " + token} if token else {}
    # Job-scoped Git operations are idempotent on the server; a lost response can be retried.
    attempts = 8 if body.get("jobId") else 1
    for attempt in range(attempts):
        try:
            response = httpx.post(f"{base}/v1/tools/{tool}", json=body, headers=headers, timeout=60)
            break
        except httpx.TransportError:
            if attempt + 1 == attempts:
                raise
            time.sleep(min(0.5 * (attempt + 1), 2.0))
    try:
        payload = response.json()
    except ValueError:
        payload = {}
    if response.status_code >= 400:
        message = str(payload.get("message") or response.text)
        raise ValueError(message or "Git 服务调用失败")
    return payload if isinstance(payload, dict) else {}
