import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from starlette.testclient import TestClient

from git_ci.github import GitHub
from git_ci.github import GitHubError
from git_ci.server import call_tool, client_from_env, create_app


def test_scaffold_retries_reuse_own_commit_and_refuse_human_changes():
    class Existing(GitHub):
        def __init__(self, message):
            super().__init__("http://unused", "test")
            self.message = message
            self.calls = []
        def request(self, method, path, payload=None, **kwargs):
            self.calls.append((method, path))
            if path.endswith("/git/ref/heads/draft"):
                return {"object": {"sha": "original-sha"}}
            if path.endswith("/git/commits/original-sha"):
                return {"message": self.message}
            if "pulls?" in path:
                return [{"number": 5, "html_url": "https://github.com/keel-agents/qa/pull/5", "draft": True}]
            return {"description": "keel-devflow:DF-9001", "private": True}

    own = Existing("Keel scaffold DF-9001: initial")
    assert own.push_scaffold("qa", "DF-9001", "retry", []) ["sha"] == "original-sha"
    assert own.ensure_pr("qa", "DF-9001", "retry", "body")["number"] == 5
    assert not any(method in ("POST", "PATCH") for method, _ in own.calls)
    with pytest.raises(GitHubError):
        Existing("Human improvement").push_scaffold("qa", "DF-9001", "retry", [])


def test_agent_fix_commit_uses_compare_and_swap_without_overwriting_human_changes():
    class Fix(GitHub):
        def __init__(self, head="a" * 40, message="Keel build DF-9001: first pass"):
            super().__init__("http://unused", "test")
            self.head = head
            self.message = message
            self.calls = []
        def request(self, method, path, payload=None, **kwargs):
            self.calls.append((method, path, payload))
            if path.endswith("/qa"):
                return {"description": "keel-devflow:DF-9001", "private": True}
            if path.endswith("/git/ref/heads/draft"):
                return {"object": {"sha": self.head}}
            if "/git/commits/" in path and method == "GET":
                return {"message": self.message, "tree": {"sha": "old-tree"}}
            if path.endswith("/git/blobs"):
                return {"sha": "blob-1"}
            if path.endswith("/git/trees"):
                return {"sha": "new-tree"}
            if path.endswith("/git/commits"):
                return {"sha": "b" * 40}
            return {}

    github = Fix()
    result = github.push_scaffold("qa", "DF-9001", "Keel build DF-9001: fix",
                                  [{"path": "app.py", "content": "updated"}], expected_sha="a" * 40)
    assert result["sha"] == "b" * 40
    commit_payload = next(payload for method, path, payload in github.calls
                          if method == "POST" and path.endswith("/git/commits"))
    assert commit_payload["parents"] == ["a" * 40]
    tree_payload = next(payload for method, path, payload in github.calls
                        if method == "POST" and path.endswith("/git/trees"))
    assert tree_payload["base_tree"] == "old-tree"
    assert ("PATCH", "/repos/keel-agents/qa/git/refs/heads/draft",
            {"sha": "b" * 40, "force": False}) in github.calls
    with pytest.raises(GitHubError, match="Draft head changed"):
        Fix(head="c" * 40).push_scaffold("qa", "DF-9001", "fix", [], expected_sha="a" * 40)
    with pytest.raises(GitHubError, match="Human changes"):
        Fix(message="Human edit").push_scaffold("qa", "DF-9001", "fix", [], expected_sha="a" * 40)


def test_scaffold_pr_is_created_as_a_draft_and_ready_retries_are_not_misreported():
    class Capture(GitHub):
        def __init__(self, ready=False):
            super().__init__("http://unused", "test")
            self.ready = ready
            self.opened = None
        def request(self, method, path, payload=None, **kwargs):
            if "pulls?" in path:
                return [{"number": 1, "html_url": "https://github.com/keel-agents/qa/pull/1", "draft": False}] if self.ready else []
            if method == "POST" and path.endswith("/pulls"):
                self.opened = payload
                return {"number": 1, "html_url": "https://github.com/keel-agents/qa/pull/1"}
            return {"description": "keel-devflow:DF-9001", "private": True}

    client = Capture()
    assert client.ensure_pr("qa", "DF-9001", "title", "body")["number"] == 1
    assert client.opened["draft"] is True
    with pytest.raises(GitHubError, match="not a draft"):
        Capture(ready=True).ensure_pr("qa", "DF-9001", "title", "body")


def test_personal_repository_uses_the_authenticated_user_endpoint():
    class Personal(GitHub):
        def __init__(self):
            super().__init__("http://unused", "test", "owner")
            self.paths = []
        def request(self, method, path, payload=None, **kwargs):
            self.paths.append((method, path))
            return {"type": "User", "login": "owner"}
    client = Personal()
    client.create_repo("qa")
    assert ("POST", "/user/repos") in client.paths
    assert ("POST", "/orgs/owner/repos") not in client.paths


def test_plan_limit_keeps_private_scaffolds_but_blocks_merge_and_other_permission_failures():
    class Restricted(GitHub):
        def __init__(self, message):
            super().__init__("http://unused", "test")
            self.message = message
            self.calls = []
        def request(self, method, path, payload=None, **kwargs):
            self.calls.append((method, path))
            if path.endswith("/protection"):
                raise GitHubError(403, self.message)
            return {"description": "keel-devflow:DF-9001", "private": True}

    plan = Restricted("Upgrade to GitHub Pro or make this repository public to enable this feature.")
    assert plan.ensure_repo("qa", "DF-9001")["protection"] == "UNAVAILABLE"
    with pytest.raises(GitHubError):
        plan.merge("qa", 1)
    assert not any(path.endswith("/merge") for _, path in plan.calls)
    with pytest.raises(GitHubError):
        Restricted("Resource not accessible by integration").ensure_repo("qa", "DF-9001")


def test_human_pr_review_is_bound_to_protected_main_and_exact_candidate_sha():
    sha = "a" * 40

    class Reviews(GitHub):
        def __init__(self, *, protected=True, draft=False, head=sha, latest="APPROVED", review_sha=sha):
            super().__init__("http://unused", "test")
            self.protected, self.draft, self.head = protected, draft, head
            self.latest, self.review_sha = latest, review_sha

        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/qa"):
                return {"description": "keel-devflow:DF-9001", "private": True}
            if path.endswith("/pulls/7"):
                return {"head": {"sha": self.head}, "base": {"ref": "main"},
                        "state": "open", "draft": self.draft}
            if path.endswith("/branches/main/protection"):
                if not self.protected:
                    raise GitHubError(403, "Upgrade to GitHub Pro")
                return {"required_pull_request_reviews": {"required_approving_review_count": 1}}
            if path.endswith("/pulls/7/reviews?per_page=100"):
                return [{"user": {"login": "human", "type": "User"},
                         "state": self.latest, "commit_id": self.review_sha}]
            raise AssertionError(path)

    assert call_tool(Reviews(), "git.pr.review.status",
                     {"repo": "qa", "number": 7, "sha": sha, "jobId": "DF-9001"})["approved"] is True
    assert Reviews(draft=True).review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(protected=False).review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(review_sha="b" * 40).review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(latest="CHANGES_REQUESTED").review_status("qa", 7, sha, "DF-9001")["approved"] is False
    with pytest.raises(GitHubError, match="stale"):
        Reviews(head="b" * 40).review_status("qa", 7, sha, "DF-9001")


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


def test_runtime_requires_service_auth_before_accessing_github():
    github = GitHub("https://api.github.com", "token")
    client = TestClient(create_app(github, "internal-token"))
    assert client.get("/v1/health").status_code == 200
    response = client.post("/v1/tools/git.pr.diff", json={"repo": "example", "number": 1})
    assert response.status_code == 401
    assert response.json()["code"] == "AUTH_UNAUTHENTICATED"


def test_snapshot_uses_exact_private_task_commit_and_returns_utf8_files():
    class Snapshot(GitHub):
        def __init__(self):
            super().__init__("http://unused", "test", "owner")
            self.paths = []
        def request(self, method, path, payload=None, **kwargs):
            self.paths.append(path)
            if path.endswith("/qa"):
                return {"description": "keel-devflow:DF-9001", "private": True}
            if "/git/commits/" in path:
                return {"sha": "a" * 40, "tree": {"sha": "tree-1"}}
            if "/git/trees/" in path:
                return {"truncated": False, "tree": [
                    {"type": "blob", "mode": "100644", "path": "app.py", "sha": "blob-1"}]}
            if "/git/blobs/" in path:
                return {"encoding": "base64", "content": base64.b64encode(b"print('ok')\n").decode()}
            raise AssertionError(path)

    github = Snapshot()
    result = call_tool(github, "git.repo.snapshot", {"repo": "qa", "sha": "a" * 40, "jobId": "DF-9001"})
    assert result == {"repo": "owner/qa", "sha": "a" * 40,
                      "files": [{"path": "app.py", "content": "print('ok')\n"}]}
    assert f"/repos/owner/qa/git/commits/{'a' * 40}" in github.paths
    with pytest.raises(ValueError):
        call_tool(github, "git.repo.snapshot", {"repo": "qa", "sha": "draft", "jobId": "DF-9001"})


def test_snapshot_rejects_other_jobs_and_unsafe_paths():
    class Unsafe(GitHub):
        def __init__(self, path="../secret", owner="keel-devflow:DF-9001"):
            super().__init__("http://unused", "test")
            self.path = path
            self.owner = owner
        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/qa"):
                return {"description": self.owner, "private": True}
            if "/git/commits/" in path:
                return {"sha": "a" * 40, "tree": {"sha": "tree-1"}}
            if "/git/trees/" in path:
                return {"truncated": False, "tree": [
                    {"type": "blob", "mode": "100644", "path": self.path, "sha": "blob-1"}]}
            raise AssertionError(path)

    with pytest.raises(GitHubError, match="Unsafe repository path"):
        Unsafe().snapshot("qa", "a" * 40, "DF-9001")
    with pytest.raises(GitHubError, match="not the private repository"):
        Unsafe(owner="keel-devflow:DF-9002").snapshot("qa", "a" * 40, "DF-9001")
