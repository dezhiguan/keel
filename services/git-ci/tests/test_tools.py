import base64
import io
import json
import threading
import zipfile
import httpx
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from starlette.testclient import TestClient

from git_ci.github import GitHub
from git_ci.github import GitHubError
from git_ci.server import call_tool, client_from_env, create_app


def test_ci_logs_follow_redirect_and_unpack_zip(monkeypatch):
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as archive:
        archive.writestr("build/1_job.txt", "Compilation failed on line 12")
    seen = []

    class Response:
        status_code = 200
        content = payload.getvalue()

    def request(method, url, **kwargs):
        seen.append({"method": method, "url": url, "redirects": kwargs.get("follow_redirects")})
        return Response()

    monkeypatch.setattr("git_ci.github.httpx.request", request)
    result = GitHub("https://api.github.test", "token", "owner").logs("repo", "123")
    assert seen[0] == {"method": "GET", "url": "https://api.github.test/repos/owner/repo/actions/runs/123/logs", "redirects": True}
    assert "Compilation failed on line 12" in result["log"]
    assert not result["truncated"]


def test_ci_logs_relay_only_the_expected_signed_archive_host(monkeypatch):
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as archive:
        archive.writestr("build.txt", "Failure at compile")
    calls = []

    def request(method, url, **kwargs):
        calls.append((url, kwargs.get("headers", {})))
        if url.endswith("/logs"):
            return type("Redirect", (), {"status_code": 302,
                "headers": {"location": "https://results-receiver.actions.githubusercontent.com/signed/archive?sig=opaque"}})()
        return type("Archive", (), {"status_code": 200, "content": payload.getvalue()})()

    monkeypatch.setattr("git_ci.github.httpx.request", request)
    result = GitHub("https://api.github.test", "token", "owner",
                    log_archive_proxy="https://keel.test/_keel_ci_logs").logs("repo", "123")
    assert "Failure at compile" in result["log"]
    assert calls[1][0] == "https://keel.test/_keel_ci_logs/signed/archive?sig=opaque"
    assert "Authorization" not in calls[1][1]


def test_ci_logs_put_failed_job_tail_before_large_successful_build(monkeypatch):
    payload = io.BytesIO()
    with zipfile.ZipFile(payload, "w") as archive:
        archive.writestr("0_success.txt", "ok\n" * 20000)
        archive.writestr("1_failed.txt", "work\n" * 5000 + "##[error] rollout failed\n")

    class Response:
        status_code = 200

        def __init__(self, content):
            self.content = content

        def json(self):
            return json.loads(self.content)

    def request(method, url, **kwargs):
        if url.endswith("/jobs?per_page=100"):
            return Response(b'{"jobs":[{"name":"failed","conclusion":"failure","steps":[{"name":"rollout","conclusion":"failure"}]}]}')
        return Response(payload.getvalue())

    monkeypatch.setattr("git_ci.github.httpx.request", request)
    result = GitHub("https://api.github.test", "token", "owner").logs("repo", "123")
    assert "失败 Job: failed; 失败步骤: rollout" in result["log"][:300]
    assert "##[error] rollout failed" in result["log"][:6000]


def test_ci_log_connection_timeout_is_a_bounded_upstream_error(monkeypatch):
    seen = []

    def timeout(method, url, **kwargs):
        seen.append((url, kwargs["timeout"]))
        if url.endswith("/logs"):
            raise httpx.ConnectTimeout("unavailable")
        return type("Response", (), {"status_code": 200, "content": b'{}',
                                     "json": lambda self: {"jobs": [{"name": "build", "conclusion": "failure",
                                                               "steps": [{"name": "compile", "conclusion": "failure"}]}]}})()

    monkeypatch.setattr("git_ci.github.httpx.request", timeout)
    result = GitHub("https://api.github.test", "token", "owner").logs("repo", "123")
    assert result["logsUnavailable"] is True
    assert "failed step: compile" in result["log"]
    assert [timeout for _, timeout in seen] == [10, 10]


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
    assert tree_payload["tree"] == [{"path": "app.py", "mode": "100644", "type": "blob", "content": "updated"}]
    assert not any(path.endswith("/git/blobs") for _, path, _ in github.calls)
    assert ("PATCH", "/repos/keel-agents/qa/git/refs/heads/draft",
            {"sha": "b" * 40, "force": False}) in github.calls
    with pytest.raises(GitHubError, match="Draft head changed"):
        Fix(head="c" * 40).push_scaffold("qa", "DF-9001", "fix", [], expected_sha="a" * 40)
    with pytest.raises(GitHubError, match="Human changes"):
        Fix(message="Human edit").push_scaffold("qa", "DF-9001", "fix", [], expected_sha="a" * 40)


def test_lost_push_response_recovers_only_direct_task_commit_without_writing():
    class Lost(GitHub):
        def __init__(self, parent="a" * 40, message="Keel build DF-9001: fix"):
            super().__init__("http://unused", "test")
            self.parent, self.message = parent, message
        def request(self, method, path, payload=None, **kwargs):
            assert method == "GET" or (method == "PUT" and path.endswith(("/protection", "/permissions", "/workflow"))), "Recovery must not rewrite the branch"
            if path.endswith("/qa"):
                return {"description": "keel-devflow:DF-9001", "private": True}
            if path.endswith("/git/ref/heads/draft"):
                return {"object": {"sha": "b" * 40}}
            if "/git/commits/" in path:
                return {"message": self.message, "parents": [{"sha": self.parent}]}
            return {}

    result = Lost().push_scaffold("qa", "DF-9001", "retry", [], expected_sha="a" * 40)
    assert result["sha"] == "b" * 40 and result["recovered"] is True
    with pytest.raises(GitHubError, match="Draft head changed"):
        Lost(parent="c" * 40).push_scaffold("qa", "DF-9001", "retry", [], expected_sha="a" * 40)
    with pytest.raises(GitHubError, match="Human changes"):
        Lost(message="Human edit").push_scaffold("qa", "DF-9001", "retry", [], expected_sha="a" * 40)


def test_scaffold_pr_is_created_as_a_draft_and_ready_repair_reuses_same_pr():
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
    ready = Capture(ready=True)
    reused = ready.ensure_pr("qa", "DF-9001", "title", "body")
    assert reused["number"] == 1
    assert reused["draft"] is False
    assert ready.opened is None


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


def test_public_devflow_repo_requires_protected_main_and_valid_visibility():
    class Public(GitHub):
        def __init__(self):
            super().__init__("http://unused", "test", devflow_visibility="public")
            self.created = None
            self.protection = None

        def _create_repo(self, payload):
            self.created = payload

        def request(self, method, path, payload=None, **kwargs):
            if method == "GET" and path.endswith("/qa"):
                raise GitHubError(404, "missing")
            if path.endswith("/branches/main/protection"):
                self.protection = payload
            return {}

        def status(self, method, path):
            return 404

    client = Public()
    assert client.ensure_repo("qa", "DF-9001")["protection"] == "ENFORCED"
    assert client.created == {"name": "qa", "private": False, "auto_init": True,
                              "description": "keel-devflow:DF-9001"}
    assert client.protection["enforce_admins"] is True
    assert client.protection["required_pull_request_reviews"]["required_approving_review_count"] == 1
    with pytest.raises(ValueError, match="KEEL_DEVFLOW_REPO_VISIBILITY"):
        GitHub("http://unused", "test", devflow_visibility="internal")


def test_ensure_repo_sets_public_server_url_as_actions_variable():
    class Configured(GitHub):
        def __init__(self):
            super().__init__("http://unused", "test", server_url="https://keel.example/")
            self.calls = []

        def request(self, method, path, payload=None, **kwargs):
            self.calls.append((method, path, payload))
            if path.endswith("/protection"):
                raise GitHubError(403, "Upgrade to GitHub Pro")
            return {"description": "keel-devflow:DF-9001", "private": True}

        def status(self, method, path):
            return 404

    client = Configured()
    client.ensure_repo("qa", "DF-9001")
    assert ("POST", "/repos/keel-agents/qa/actions/variables",
            {"name": "KEEL_SERVER_URL", "value": "https://keel.example"}) in client.calls


def test_human_pr_review_is_bound_to_protected_main_and_exact_candidate_sha():
    sha = "a" * 40

    class Reviews(GitHub):
        def __init__(self, *, protected=True, draft=False, head=sha, latest="APPROVED", review_sha=sha,
                     private=True, reviewer_permission="write"):
            super().__init__("http://unused", "test")
            self.protected, self.draft, self.head = protected, draft, head
            self.latest, self.review_sha = latest, review_sha
            self.private = private
            self.reviewer_permission = reviewer_permission

        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/qa"):
                return {"description": "keel-devflow:DF-9001", "private": self.private}
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
            if path.endswith("/collaborators/human/permission"):
                return {"permission": self.reviewer_permission}
            raise AssertionError(path)

    assert call_tool(Reviews(), "git.pr.review.status",
                     {"repo": "qa", "number": 7, "sha": sha, "jobId": "DF-9001"})["approved"] is True
    assert Reviews(private=False).review_status("qa", 7, sha, "DF-9001")["approved"] is True
    assert Reviews(draft=True).review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(protected=False).review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(review_sha="b" * 40).review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(latest="CHANGES_REQUESTED").review_status("qa", 7, sha, "DF-9001")["approved"] is False
    assert Reviews(reviewer_permission="read").review_status("qa", 7, sha, "DF-9001")["approved"] is False
    with pytest.raises(GitHubError, match="stale"):
        Reviews(head="b" * 40).review_status("qa", 7, sha, "DF-9001")

    class MergeReviews(Reviews):
        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/pulls/7/merge"):
                assert method == "PUT" and payload == {"sha": sha}
                return {"merged": True, "sha": "c" * 40}
            return super().request(method, path, payload, **kwargs)

    result = call_tool(MergeReviews(), "git.pr.merge", {"repo": "qa", "number": 7,
                                                  "jobId": "DF-9001", "expectedSha": sha})
    assert result["merged"] is True and result["headSha"] == sha
    with pytest.raises(GitHubError, match="job and exact reviewed commit"):
        MergeReviews().merge("qa", 7)

    class AlreadyMerged(MergeReviews):
        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/pulls/7"):
                return {"head": {"sha": sha}, "merged": True, "merge_commit_sha": "c" * 40}
            return super().request(method, path, payload, **kwargs)

    assert AlreadyMerged().merge("qa", 7, job_id="DF-9001", expected_sha=sha)["sha"] == "c" * 40


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


def test_missing_workflow_scope_is_reported_when_github_hides_the_tree():
    class Hidden(GitHub):
        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/git/trees") and any(
                    item.get("path", "").startswith(".github/workflows/")
                    for item in (payload or {}).get("tree", [])):
                raise GitHubError(404, "Not Found")
            if path.endswith("/git/blobs"):
                return {"sha": "blob-1"}
            if path.endswith("/git/trees"):
                return {"sha": "tree-1"}
            raise AssertionError(path)

    client = Hidden("http://unused", "test")
    with pytest.raises(GitHubError, match="workflow scope") as caught:
        client._tree("qa", [{"path": ".github/workflows/keel.yml", "content": "name: keel\n"}], "base")
    assert caught.value.status == 403

    class Missing(GitHub):
        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/git/trees"):
                raise GitHubError(404, "Not Found")
            if path.endswith("/git/blobs"):
                return {"sha": "blob-1"}
            raise AssertionError(path)

    with pytest.raises(GitHubError, match="Not Found") as plain:
        Missing("http://unused", "test")._tree("qa", [{"path": "README.md", "content": "hi"}], "base")
    assert plain.value.status == 404


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
    assert ("PUT", "/repos/keel-agents/release-notes/actions/permissions") in paths
    workflow_permission = json.loads(next(body for method, path, body, _accept in Handler.seen
                                          if path.endswith("/actions/permissions/workflow")))
    assert workflow_permission["default_workflow_permissions"] == "read"
    assert workflow_permission["can_approve_pull_request_reviews"] is False
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


def test_produced_employee_merge_requires_a_server_only_h4_secret():
    class MergeStub:
        def merge(self, repo, number, *, job_id=None, expected_sha=None):
            return {"repo": repo, "number": number, "merged": True, "sha": "c" * 40,
                    "headSha": expected_sha}

    client = TestClient(create_app(MergeStub(), "tool-token", "h4-only-token"))
    body = {"repo": "spec-agent", "number": 1, "jobId": "DF-9001", "expectedSha": "a" * 40}
    headers = {"Authorization": "Bearer tool-token"}
    assert client.post("/v1/tools/git.pr.merge", json=body, headers=headers).status_code == 401
    approved = client.post("/v1/tools/git.pr.merge", json=body,
                           headers={**headers, "X-Keel-Merge-Token": "h4-only-token"})
    assert approved.status_code == 200 and approved.json()["merged"] is True


def test_snapshot_uses_exact_task_commit_and_returns_utf8_files():
    class Snapshot(GitHub):
        def __init__(self):
            super().__init__("http://unused", "test", "owner")
            self.paths = []
        def request(self, method, path, payload=None, **kwargs):
            self.paths.append(path)
            if path.endswith("/qa"):
                return {"description": "keel-devflow:DF-9001", "private": False}
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
    with pytest.raises(GitHubError, match="not the repository"):
        Unsafe(owner="keel-devflow:DF-9002").snapshot("qa", "a" * 40, "DF-9001")


def test_snapshot_skips_directories_and_rejects_symlinks():
    class Tree(GitHub):
        def __init__(self, entries):
            super().__init__("http://unused", "test", "keel-agents")
            self.entries = entries
        def request(self, method, path, payload=None, **kwargs):
            if path.endswith("/spec-agent"):
                return {"description": "keel-devflow:DF-0002", "private": True}
            if "/git/commits/" in path:
                return {"sha": "b" * 40, "tree": {"sha": "tree-1"}}
            if "/git/trees/" in path:
                return {"truncated": False, "tree": self.entries}
            if path.endswith("/blob-role"):
                return {"encoding": "base64", "content": base64.b64encode(b"def execute():\n    return {}\n").decode()}
            raise AssertionError(path)

    result = Tree([
        {"type": "tree", "mode": "040000", "path": "tests", "sha": "tree-2"},
        {"type": "blob", "mode": "100644", "path": "tests/test_role.py", "sha": "blob-role"},
    ]).snapshot("spec-agent", "b" * 40, "DF-0002")
    assert result["files"] == [{"path": "tests/test_role.py", "content": "def execute():\n    return {}\n"}]
    with pytest.raises(GitHubError, match="regular files only"):
        Tree([{"type": "blob", "mode": "120000", "path": "link", "sha": "blob-role"}]).snapshot(
            "spec-agent", "b" * 40, "DF-0002")
