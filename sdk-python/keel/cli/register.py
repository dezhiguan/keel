"""Register the current agent.yaml with keel-server."""

import hashlib
import json
import os
import re
import subprocess
from pathlib import Path

import yaml

from keel.cli.server import ServerRejected, post_json
from keel.manifest import load_manifest


def register(*, env: str, manifest_file: str = "agent.yaml") -> dict:
    path = Path(manifest_file)
    manifest = load_manifest(path)
    body = yaml.safe_load(path.read_text(encoding="utf-8"))
    files = _prompt_files(path.parent, body)
    git_sha = _git_sha(path.parent) if files else None
    body["env"] = env
    try:
        report = post_json("/api/v1/agents", body)
        if files:
            post_json(f"/api/v1/agents/{manifest.metadata.name}/prompts/sync",
                      {"gitSha": git_sha, "files": files})
    except ServerRejected as error:
        raise ValueError(f"{error.code} {error}") from error
    print(f"registered {manifest.metadata.name} env={env}")
    return report


def _prompt_files(root: Path, manifest: dict) -> list[dict]:
    declared = (manifest.get("spec") or {}).get("prompts") or {}
    if declared.get("source", "langfuse") != "langfuse":
        return []
    files = []
    for item in declared.get("items") or []:
        name, kind = item["name"], item["type"]
        path = root / "prompts" / f"{name}.{'json' if kind == 'chat' else 'md'}"
        if not path.is_file():
            raise ValueError(f"缺少声明的提示词文件：{path}")
        if kind == "chat":
            prompt = json.loads(path.read_text(encoding="utf-8"))
            if not isinstance(prompt, list):
                raise ValueError(f"chat 提示词必须是消息数组：{path}")
            canonical = json.dumps([{"role": row["role"], "content": row["content"]} for row in prompt],
                                   ensure_ascii=False, separators=(",", ":"))
        else:
            prompt = path.read_text(encoding="utf-8")
            canonical = prompt
        if not canonical:
            raise ValueError(f"提示词不能为空：{path}")
        files.append({"name": name, "type": kind, "prompt": prompt,
                      "sha256": "sha256:" + hashlib.sha256(canonical.encode("utf-8")).hexdigest()})
    return files


def _git_sha(root: Path) -> str:
    configured = os.environ.get("GITHUB_SHA", "").strip()
    if re.fullmatch(r"[0-9a-fA-F]{40}", configured):
        return configured.lower()
    result = subprocess.run(["git", "rev-parse", "--verify", "HEAD"], cwd=root,
                            text=True, capture_output=True, check=False)
    if result.returncode == 0 and re.fullmatch(r"[0-9a-fA-F]{40}", result.stdout.strip()):
        return result.stdout.strip().lower()
    raise ValueError("同步提示词前请提交项目代码，或设置 GITHUB_SHA")
