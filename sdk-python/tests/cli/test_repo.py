import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

import pytest

from keel.cli.repo import _files, _post, open_draft


class _Git(ThreadingHTTPServer):
    def __init__(self):
        self.calls = []
        self.fail = False

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                self.server.calls.append((self.path, body))
                if self.server.fail:
                    raw = '{"code":"GIT_UPSTREAM_FAILED","message":"GitHub 调用失败","retryable":true}'.encode()
                    self.send_response(502)
                    self.send_header("Content-Length", str(len(raw)))
                    self.end_headers()
                    self.wfile.write(raw)
                    return
                tool = self.path.rsplit("/", 1)[-1]
                payload = {"repo": "keel-agents/spec-agent"}
                if tool == "git.pr.open":
                    payload["number"] = 7
                if tool == "git.pr.merge":
                    self.send_response(500)
                else:
                    self.send_response(200)
                raw = json.dumps(payload).encode()
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def log_message(self, _format, *_args):
                return

        super().__init__(("127.0.0.1", 0), Handler)


def test_unset_url_does_not_call_git(tmp_path, monkeypatch):
    monkeypatch.delenv("KEEL_GIT_CI_URL", raising=False)
    assert open_draft(tmp_path, title="需求分析师", body="起草") is None


def test_files_under_a_checkpoint_folder_are_included(tmp_path):
    project = tmp_path / ".keel" / "meta-agent" / "DF-9001" / "spec-agent"
    project.mkdir(parents=True)
    (project / "app.py").write_text("print('ready')\n", encoding="utf-8")
    (project / ".keel").mkdir()
    (project / ".keel" / "state.json").write_text("{}", encoding="utf-8")

    assert _files(project) == [{"path": "app.py", "content": "print('ready')\n"}]


def test_job_scoped_git_write_retries_lost_connections_without_repeating_legacy_calls(monkeypatch):
    import httpx
    calls = []

    def unstable_post(url, **kwargs):
        calls.append((url, kwargs['json']))
        if len(calls) in (1, 3):
            raise httpx.ConnectError('connection reset')
        return httpx.Response(200, json={'repo': 'dezhiguan/qa', 'number': 1})

    monkeypatch.setattr(httpx, 'post', unstable_post)
    monkeypatch.setattr('keel.cli.repo.time.sleep', lambda _: None)
    assert _post('http://git-ci', 'git.pr.open', {'jobId': 'DF-9001'})['number'] == 1
    assert len(calls) == 2
    with pytest.raises(httpx.ConnectError):
        _post('http://git-ci', 'git.pr.open', {'repo': 'qa'})
    assert len(calls) == 3


def test_job_scoped_git_write_survives_seven_transient_disconnects(monkeypatch):
    import httpx
    calls = []

    def unstable_post(url, **kwargs):
        calls.append(url)
        if len(calls) < 8:
            raise httpx.ConnectError('connection refused')
        return httpx.Response(200, json={'sha': 'a' * 40})

    monkeypatch.setattr(httpx, 'post', unstable_post)
    monkeypatch.setattr('keel.cli.repo.time.sleep', lambda _: None)
    assert _post('http://git-ci', 'git.branch.push', {'jobId': 'DF-9001'})['sha'] == 'a' * 40
    assert len(calls) == 8


def test_opens_a_draft_pull_request_and_does_not_merge(tmp_path, monkeypatch):
    project = tmp_path / "spec-agent"
    project.mkdir()
    (project / "agent.yaml").write_text(
        "apiVersion: keel/v1\nkind: Agent\nmetadata:\n  name: spec-agent\n"
        "spec:\n  runtime:\n    endpoint: http://spec-agent.agents.svc:8000\n",
        encoding="utf-8")
    (project / "app.py").write_text("print('skeleton')\n", encoding="utf-8")
    server = _Git()
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        monkeypatch.setenv("KEEL_GIT_CI_URL", f"http://127.0.0.1:{server.server_address[1]}")
        opened = open_draft(project, title="需求分析师", body="起草需求单")
    finally:
        server.shutdown()
    assert opened["number"] == 7
    paths = [path for path, _ in server.calls]
    assert paths == ["/v1/tools/git.repo.create", "/v1/tools/git.branch.push", "/v1/tools/git.pr.open"]
    create, push, pr = (body for _, body in server.calls)
    assert create == {"name": "spec-agent"}
    assert push["branch"] == "draft"
    assert {item["path"] for item in push["files"]} == {"agent.yaml", "app.py"}
    assert "spec-agent" in next(item["content"] for item in push["files"] if item["path"] == "agent.yaml")
    assert pr == {"repo": "spec-agent", "head": "draft", "base": "main", "title": "需求分析师", "body": "起草需求单"}


def test_upstream_failure_is_reported(tmp_path, monkeypatch):
    project = tmp_path / "spec-agent"
    project.mkdir()
    (project / "agent.yaml").write_text(
        "apiVersion: keel/v1\nkind: Agent\nmetadata:\n  name: spec-agent\n"
        "spec:\n  runtime:\n    endpoint: http://spec-agent.agents.svc:8000\n",
        encoding="utf-8")

    server = _Git()
    server.fail = True
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        monkeypatch.setenv("KEEL_GIT_CI_URL", f"http://127.0.0.1:{server.server_address[1]}")
        with pytest.raises(ValueError, match="GitHub"):
            open_draft(project, title="需求分析师", body="起草")
    finally:
        server.shutdown()
