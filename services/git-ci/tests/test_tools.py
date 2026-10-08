import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from starlette.testclient import TestClient

from git_ci.github import GitHub
from git_ci.server import call_tool, client_from_env, create_app


class Handler(BaseHTTPRequestHandler):
    seen = []

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()

    def do_PUT(self):
        self._handle()

    def do_PATCH(self):
        self._handle()

    def _handle(self):
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length)
        Handler.seen.append((self.command, self.path, body, self.headers.get("Accept")))
        if self.path.endswith("/git/ref/heads/feature"):
            self.send_response(404)
            self.end_headers()
            return
        if self.path.endswith("/logs"):
            raw = b"z" * 70_000
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return
        if "diff" in (self.headers.get("Accept") or ""):
            raw = b"diff --git a/a b/a"
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)
            return
        if self.path.endswith("/dispatches"):
            self.send_response(204)
            self.end_headers()
            return
        raw = json.dumps({
            "sha": "commit1",
            "number": 7,
            "object": {"sha": "parent"},
            "tree": {"sha": "tree0"},
        }).encode()
        self.send_response(201 if self.command == "POST" else 200)
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def log_message(self, *_args):
        return


def test_tools_stay_inside_keel_agents_and_protect_main():
    Handler.seen = []
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    github = GitHub(f"http://127.0.0.1:{server.server_address[1]}", "token")
    try:
        with pytest.raises(ValueError):
            call_tool(github, "git.repo.create", {"name": "Keel/other"})
        assert Handler.seen == []
        created = call_tool(github, "git.repo.create", {"name": "release-notes"})
        pushed = call_tool(github, "git.branch.push", {
            "repo": "release-notes", "branch": "feature", "message": "add agent",
            "files": [{"path": "agent.yaml", "content": "apiVersion: keel/v1"}],
        })
        opened = call_tool(github, "git.pr.open", {
            "repo": "release-notes", "head": "feature", "base": "main",
            "title": "add", "body": "please review",
        })
        diff = call_tool(github, "git.pr.diff", {"repo": "release-notes", "number": 7})
        call_tool(github, "git.pr.comment", {"repo": "release-notes", "number": 7, "body": "looks fine"})
        merged = call_tool(github, "git.pr.merge", {"repo": "release-notes", "number": 7})
        call_tool(github, "ci.workflow.dispatch", {
            "repo": "release-notes", "workflow": "keel.yml", "ref": "main", "inputs": {"env": "staging"},
        })
        logs = call_tool(github, "ci.log.fetch", {"repo": "release-notes", "runId": "99"})
    finally:
        server.shutdown()
        server.server_close()

    paths = [(item[0], item[1]) for item in Handler.seen]
    assert created["repo"] == "keel-agents/release-notes"
    assert ("POST", "/orgs/keel-agents/repos") in paths
    assert ("PUT", "/repos/keel-agents/release-notes/branches/main/protection") in paths
    protection = json.loads(next(body for method, path, body, _accept in Handler.seen
                                 if path.endswith("/protection")))
    assert protection["required_pull_request_reviews"]["required_approving_review_count"] == 1
    assert pushed["sha"] == "commit1"
    assert ("POST", "/repos/keel-agents/release-notes/git/refs") in paths
    assert opened["number"] == 7
    assert diff["diff"].startswith("diff --git")
    assert merged["sha"] == "commit1"
    assert ("POST", "/repos/keel-agents/release-notes/actions/workflows/keel.yml/dispatches") in paths
    assert logs["truncated"] is True
    assert len(logs["log"]) == 64 * 1024

    client = TestClient(create_app(github))
    failed = client.post("/v1/tools/git.pr.merge", json={"repo": "Bad", "number": 1})
    assert failed.status_code == 400
    assert failed.json()["code"] == "SERVER_INVALID_PARAM"


def test_missing_github_env_is_rejected(monkeypatch):
    monkeypatch.delenv("KEEL_GITHUB_TOKEN", raising=False)
    monkeypatch.delenv("KEEL_GITHUB_API", raising=False)
    with pytest.raises(ValueError):
        client_from_env()
