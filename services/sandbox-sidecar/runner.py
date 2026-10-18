"""Run the exact Git commit snapshot with in-pod fakes and no external credentials."""

import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request


MAX_FILES = 100
MAX_BYTES = 700 * 1024
TRUSTED_CHECKS = Path(__file__).with_name("platform_contract_test.py")


def check(llm_url, langfuse_url, *, snapshot_path=None, work_dir=None, attempts=20, pause=0.25):
    snapshot_path = Path(snapshot_path or os.environ.get("KEEL_SANDBOX_SNAPSHOT", "/source/bundle.json"))
    work_dir = Path(work_dir or "/work/repo")
    llm = _post(llm_url + "/v1/chat/completions", attempts, pause)
    langfuse = _post(langfuse_url + "/api/public/otel/v1/traces", attempts, pause)
    if not llm or not langfuse:
        return f"fake_llm={'ok' if llm else 'down'} fake_langfuse={'ok' if langfuse else 'down'}", False
    if not snapshot_path.is_file():
        return "snapshot=missing tests=NOT_RUN", False
    try:
        snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
        sha = snapshot.get("sha")
        if not isinstance(sha, str) or not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("snapshot SHA invalid")
        expected = os.environ.get("KEEL_SANDBOX_REF", "")
        if expected and sha != expected:
            raise ValueError("snapshot SHA differs from requested commit")
        files = snapshot.get("files")
        if not isinstance(files, list) or not files or len(files) > MAX_FILES:
            raise ValueError("snapshot file count invalid")
        used = 0
        for item in files:
            if not isinstance(item, dict):
                raise ValueError("snapshot file invalid")
            name, content = item.get("path"), item.get("content")
            if (not isinstance(name, str) or not name or name.startswith("/") or "\\" in name
                    or any(part in ("", ".", "..") for part in name.split("/"))
                    or not isinstance(content, str)):
                raise ValueError("snapshot path or content invalid")
            used += len(content.encode("utf-8"))
            if used > MAX_BYTES:
                raise ValueError("snapshot exceeds byte limit")
            destination = work_dir / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(content, encoding="utf-8")
        if not all((work_dir / name).is_file() for name in ("app.py", "agent.yaml")):
            raise ValueError("agent entry or manifest missing")
        if not (work_dir / "tests").is_dir() or not any((work_dir / "tests").glob("test_*.py")):
            raise ValueError("business tests missing")
        injected = (work_dir / "role.py").is_file()
        if injected:
            # This copy comes from the trusted runner image, outside the Git snapshot.
            # A legacy candidate file cannot disable or replace these assertions.
            (work_dir / "tests/test_platform_contract.py").write_text(
                TRUSTED_CHECKS.read_text(encoding="utf-8"), encoding="utf-8")
    except (OSError, UnicodeError, json.JSONDecodeError, ValueError) as exc:
        return f"snapshot=invalid reason={exc} tests=NOT_RUN", False

    env = {key: value for key, value in os.environ.items() if key not in (
        "KEEL_GITHUB_TOKEN", "KEEL_GIT_CI_TOKEN", "KEEL_META_AGENT_TOKEN")}
    env.update({"KEEL_LLM_BASE_URL": llm_url, "KEEL_LLM_KEY": "sandbox-test-only",
                "LANGFUSE_HOST": langfuse_url, "PYTHONDONTWRITEBYTECODE": "1"})
    try:
        result = subprocess.run([sys.executable, "-m", "pytest", "tests", "-q"], cwd=work_dir,
                                env=env, capture_output=True, text=True, timeout=180, check=False)
    except subprocess.TimeoutExpired:
        return f"sha={sha} snapshot=ok tests=TIMEOUT", False
    output = (result.stdout + "\n" + result.stderr).strip()
    report = f"sha={sha} snapshot=ok tests={'PASS' if result.returncode == 0 else 'FAIL'}\n"
    report += ("trusted_checks=injected\n" if injected else "") + output
    return report[:64 * 1024], result.returncode == 0


def _post(url, attempts, pause):
    for _ in range(attempts):
        try:
            request = urllib.request.Request(url, data=b"{}", method="POST")
            with urllib.request.urlopen(request, timeout=2) as response:
                response.read()
                if response.status == 200:
                    return True
        except (urllib.error.URLError, TimeoutError, ConnectionError):
            time.sleep(pause)
    return False


if __name__ == "__main__":
    text, ok = check(
        os.environ.get("KEEL_LLM_BASE_URL", "http://127.0.0.1:8088"),
        os.environ.get("LANGFUSE_HOST", "http://127.0.0.1:3000"),
    )
    print(text)
    raise SystemExit(0 if ok else 1)
