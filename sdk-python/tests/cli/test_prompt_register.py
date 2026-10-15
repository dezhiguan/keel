import hashlib
import json

import httpx
import pytest

from keel.cli.register import register


def _project(tmp_path, content="岗位任务：{{input}}\n"):
    (tmp_path / "prompts").mkdir()
    (tmp_path / "prompts/role.md").write_text(content, encoding="utf-8")
    (tmp_path / "agent.yaml").write_text("""apiVersion: keel/v1
kind: Agent
metadata:
  name: prompt-agent
spec:
  runtime:
    endpoint: http://127.0.0.1:8000
  prompts:
    source: langfuse
    items:
      - name: role
        type: text
""", encoding="utf-8")
    return tmp_path / "agent.yaml"


def test_register_syncs_declared_prompt_after_registration(tmp_path, monkeypatch):
    manifest = _project(tmp_path)
    monkeypatch.setenv("KEEL_SERVER_URL", "https://server.test")
    monkeypatch.setenv("GITHUB_SHA", "a" * 40)
    requests = []

    def handle(request):
        requests.append((request.url.path, json.loads(request.content)))
        return httpx.Response(200, json={"code": "OK", "data": {"passed": True}})

    with httpx.Client(transport=httpx.MockTransport(handle)) as client:
        monkeypatch.setattr(httpx, "post", lambda url, **kwargs: client.post(url, json=kwargs["json"]))
        assert register(env="staging", manifest_file=str(manifest))["passed"] is True
    assert [path for path, _ in requests] == ["/api/v1/agents", "/api/v1/agents/prompt-agent/prompts/sync"]
    payload = requests[1][1]
    assert payload["gitSha"] == "a" * 40
    assert payload["files"] == [{"name": "role", "type": "text", "prompt": "岗位任务：{{input}}\n",
                                 "sha256": "sha256:" + hashlib.sha256("岗位任务：{{input}}\n".encode()).hexdigest()}]


def test_missing_prompt_fails_before_registration(tmp_path, monkeypatch):
    manifest = _project(tmp_path)
    (tmp_path / "prompts/role.md").unlink()
    monkeypatch.setenv("GITHUB_SHA", "a" * 40)
    with pytest.raises(ValueError, match="缺少声明的提示词"):
        register(env="dev", manifest_file=str(manifest))


def test_prompt_requires_commit_before_registration(tmp_path, monkeypatch):
    manifest = _project(tmp_path)
    monkeypatch.delenv("GITHUB_SHA", raising=False)
    with pytest.raises(ValueError, match="提交项目代码"):
        register(env="dev", manifest_file=str(manifest))
