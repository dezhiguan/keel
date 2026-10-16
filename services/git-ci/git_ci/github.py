"""GitHub REST calls for repositories under keel-agents. No host default."""

import base64
import binascii
import httpx
import io
import re
import zipfile
from urllib.parse import urlsplit

ORG = "keel-agents"
LOG_LIMIT = 64 * 1024
SNAPSHOT_LIMIT = 700 * 1024
SNAPSHOT_FILES = 100
PROTECTION = {
    "required_status_checks": None,
    "enforce_admins": False,
    "required_pull_request_reviews": {"required_approving_review_count": 1},
    "restrictions": None,
}


class GitHubError(Exception):
    def __init__(self, status: int, body: str):
        super().__init__(body)
        self.status = status


class GitHub:
    def __init__(self, base_url: str, token: str, org: str = ORG, server_url: str = "",
                 log_archive_proxy: str = ""):
        if not base_url or not token:
            raise ValueError("KEEL_GITHUB_API 或 KEEL_GITHUB_TOKEN 未设置")
        self.base = base_url.rstrip("/")
        self.token = token
        self.org = org
        self.server_url = server_url.rstrip("/")
        self.log_archive_proxy = log_archive_proxy.rstrip("/")

    def _download_logs(self, path: str) -> bytes:
        if not self.log_archive_proxy:
            return self.request("GET", path, binary=True)
        try:
            response = httpx.request("GET", self.base + path, headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
            }, timeout=10, follow_redirects=False)
            if response.status_code in (301, 302, 307, 308):
                target = urlsplit(response.headers.get("location", ""))
                if target.scheme != "https" or target.hostname != "results-receiver.actions.githubusercontent.com":
                    raise GitHubError(502, "Unexpected CI log archive host")
                # The signed query is forwarded only to the fixed archive host by the gateway.
                url = self.log_archive_proxy + target.path + ("?" + target.query if target.query else "")
                response = httpx.request("GET", url, timeout=20, follow_redirects=False)
            if response.status_code >= 300:
                raise GitHubError(response.status_code, "CI log archive unavailable")
            return response.content
        except httpx.HTTPError as exc:
            raise GitHubError(502, "CI log archive connection failed") from exc

    def request(self, method: str, path: str, payload: dict | None = None, accept: str | None = None,
                raw: bool = False, binary: bool = False, timeout: int = 30):
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": accept or "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        try:
            response = httpx.request(method, self.base + path, headers=headers, json=payload,
                                     timeout=10 if binary else timeout, follow_redirects=binary)
        except httpx.HTTPError as exc:
            raise GitHubError(502, "GitHub request failed") from exc
        if response.status_code >= 300:
            raise GitHubError(response.status_code, response.text)
        if binary:
            return response.content
        if raw or (accept and "diff" in accept):
            return response.text
        if not response.content:
            return {}
        try:
            return response.json()
        except ValueError:
            return response.text

    def status(self, method: str, path: str) -> int:
        response = httpx.request(method, self.base + path, headers={
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }, timeout=30)
        if response.status_code >= 300 and response.status_code != 404:
            raise GitHubError(response.status_code, response.text)
        return response.status_code

    def create_repo(self, name: str) -> dict:
        self._create_repo({"name": name, "private": True, "auto_init": True})
        self.request("PUT", f"/repos/{self.org}/{name}/branches/main/protection", PROTECTION)
        self.enable_actions(name)
        return {"repo": f"{self.org}/{name}", "defaultBranch": "main"}

    def _create_repo(self, payload: dict):
        owner = self.request("GET", f"/users/{self.org}")
        if owner.get("type") == "User":
            actor = self.request("GET", "/user")
            if actor.get("login", "").lower() != self.org.lower():
                raise GitHubError(403, "Token owner does not match the configured repository owner")
            return self.request("POST", "/user/repos", payload)
        return self.request("POST", f"/orgs/{self.org}/repos", payload)

    def ensure_repo(self, name: str, job_id: str) -> dict:
        marker = "keel-devflow:" + job_id
        try:
            existing = self.request("GET", f"/repos/{self.org}/{name}")
            if existing.get("description") != marker or not existing.get("private"):
                raise GitHubError(409, "Repository belongs to a different task")
        except GitHubError as exc:
            if exc.status != 404:
                raise
            self._create_repo({
                "name": name, "private": True, "auto_init": True, "description": marker})
        protection = "ENFORCED"
        try:
            self.request("PUT", f"/repos/{self.org}/{name}/branches/main/protection", PROTECTION)
        except GitHubError as exc:
            if exc.status != 403 or "Upgrade to GitHub Pro" not in str(exc):
                raise
            protection = "UNAVAILABLE"
        self.enable_actions(name)
        self.configure_server_url(name)
        return {"repo": f"{self.org}/{name}", "defaultBranch": "main", "protection": protection}

    def configure_server_url(self, name: str) -> None:
        """Expose only the public server address, never a platform credential."""
        if not self.server_url:
            return
        root = f"/repos/{self.org}/{name}/actions/variables"
        if self.status("GET", root + "/KEEL_SERVER_URL") == 404:
            self.request("POST", root, {"name": "KEEL_SERVER_URL", "value": self.server_url})
        else:
            self.request("PATCH", root + "/KEEL_SERVER_URL", {"name": "KEEL_SERVER_URL", "value": self.server_url})

    def enable_actions(self, name: str) -> None:
        """Turn Actions on before the first workflow commit so that push can start CI."""
        root = f"/repos/{self.org}/{name}/actions/permissions"
        self.request("PUT", root, {"enabled": True, "allowed_actions": "all"})
        self.request("PUT", root + "/workflow", {
            "default_workflow_permissions": "read",
            "can_approve_pull_request_reviews": False,
        })

    def push_scaffold(self, repo: str, job_id: str, message: str, files: list[dict],
                      expected_sha: str | None = None) -> dict:
        self.ensure_repo(repo, job_id)
        path = f"/repos/{self.org}/{repo}"
        try:
            branch = self.request("GET", path + "/git/ref/heads/draft")
        except GitHubError as exc:
            if exc.status != 404:
                raise
            branch = None
        if branch:
            sha = branch["object"]["sha"]
            commit = self.request("GET", path + f"/git/commits/{sha}")
            own = any(commit.get("message", "").startswith(prefix) for prefix in (
                f"Keel scaffold {job_id}:", f"Keel build {job_id}:"))
            if not own:
                raise GitHubError(409, "Human changes on draft; refusing to overwrite")
            if expected_sha is not None:
                if expected_sha != sha:
                    raise GitHubError(409, "Draft head changed; refusing to overwrite")
                return self._push_from_parent(repo, "draft", message, files, sha, commit["tree"]["sha"])
            return {"repo": f"{self.org}/{repo}", "branch": "draft", "sha": sha}
        if expected_sha is not None:
            raise GitHubError(409, "Draft branch missing for expected SHA")
        return self.push(repo, "draft", message, files)

    def _push_from_parent(self, repo: str, branch: str, message: str, files: list[dict],
                          parent_sha: str, tree_sha: str) -> dict:
        path = f"/repos/{self.org}/{repo}"
        built = self._tree(repo, files, tree_sha)
        created = self.request("POST", path + "/git/commits",
                               {"message": message, "tree": built["sha"], "parents": [parent_sha]})
        self.request("PATCH", path + f"/git/refs/heads/{branch}", {"sha": created["sha"], "force": False})
        return {"repo": f"{self.org}/{repo}", "branch": branch, "sha": created["sha"]}

    def ensure_pr(self, repo: str, job_id: str, title: str, body: str) -> dict:
        repository = self.ensure_repo(repo, job_id)
        existing = self.request("GET", f"/repos/{self.org}/{repo}/pulls?state=open&head={self.org}:draft&base=main")
        if existing:
            if existing[0].get("draft") is not True:
                raise GitHubError(409, "Existing scaffold PR is not a draft")
            return {"repo": f"{self.org}/{repo}", "number": existing[0]["number"], "url": existing[0]["html_url"], "protection": repository["protection"]}
        return {**self.open_pr(repo, "draft", "main", title, body, draft=True), "protection": repository["protection"]}

    def push(self, repo: str, branch: str, message: str, files: list[dict]) -> dict:
        parent = self.request("GET", f"/repos/{self.org}/{repo}/git/ref/heads/main")
        commit_sha = parent["object"]["sha"]
        commit = self.request("GET", f"/repos/{self.org}/{repo}/git/commits/{commit_sha}")
        built = self._tree(repo, files, commit["tree"]["sha"])
        created = self.request("POST", f"/repos/{self.org}/{repo}/git/commits",
                               {"message": message, "tree": built["sha"], "parents": [commit_sha]})
        if self.status("GET", f"/repos/{self.org}/{repo}/git/ref/heads/{branch}") == 404:
            self.request("POST", f"/repos/{self.org}/{repo}/git/refs",
                         {"ref": f"refs/heads/{branch}", "sha": created["sha"]})
        else:
            self.request("PATCH", f"/repos/{self.org}/{repo}/git/refs/heads/{branch}", {"sha": created["sha"]})
        return {"repo": f"{self.org}/{repo}", "branch": branch, "sha": created["sha"]}

    def _tree(self, repo: str, files: list[dict], base_tree: str) -> dict:
        root = f"/repos/{self.org}/{repo}"
        tree = []
        for item in files:
            blob = self.request("POST", root + "/git/blobs", {"content": item["content"], "encoding": "utf-8"})
            tree.append({"path": item["path"], "mode": "100644", "type": "blob", "sha": blob["sha"]})
        try:
            return self.request("POST", root + "/git/trees", {"base_tree": base_tree, "tree": tree})
        except GitHubError as exc:
            if exc.status == 404 and any(str(item.get("path", "")).startswith(".github/workflows/") for item in files):
                raise GitHubError(403, "GitHub refused .github/workflows; KEEL_GITHUB_TOKEN needs the workflow scope") from exc
            raise

    def open_pr(self, repo: str, head: str, base: str, title: str, body: str, *, draft: bool = False) -> dict:
        opened = self.request("POST", f"/repos/{self.org}/{repo}/pulls",
                              {"head": head, "base": base, "title": title, "body": body, "draft": draft})
        return {"repo": f"{self.org}/{repo}", "number": opened["number"], "url": opened.get("html_url")}

    def diff(self, repo: str, number: int) -> dict:
        text = self.request("GET", f"/repos/{self.org}/{repo}/pulls/{number}",
                            accept="application/vnd.github.diff")
        return {"repo": f"{self.org}/{repo}", "number": number, "diff": text}

    def snapshot(self, repo: str, sha: str, job_id: str) -> dict:
        """Fetch the exact private commit for an isolated runner without giving it Git credentials."""
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("sha")
        root = f"/repos/{self.org}/{repo}"
        metadata = self.request("GET", root)
        if metadata.get("description") != f"keel-devflow:{job_id}" or metadata.get("private") is not True:
            raise GitHubError(403, "Repository is not the private repository for this task")
        commit = self.request("GET", root + f"/git/commits/{sha}")
        if commit.get("sha") != sha:
            raise GitHubError(409, "Commit SHA mismatch")
        tree_sha = commit.get("tree", {}).get("sha")
        if not isinstance(tree_sha, str) or not tree_sha:
            raise GitHubError(409, "Commit tree missing")
        tree = self.request("GET", root + f"/git/trees/{tree_sha}?recursive=1")
        entries = tree.get("tree")
        if tree.get("truncated") or not isinstance(entries, list):
            raise GitHubError(413, "Repository snapshot exceeds sandbox limits")
        # Recursive trees include directory nodes. They are not files.
        blobs = [item for item in entries if not (item.get("type") == "tree" and item.get("mode") in ("040000", "40000"))]
        if len(blobs) > SNAPSHOT_FILES:
            raise GitHubError(413, "Repository snapshot exceeds sandbox limits")
        files = []
        used = 0
        for item in blobs:
            path = item.get("path")
            if item.get("type") != "blob" or item.get("mode") != "100644":
                raise GitHubError(400, "Sandbox snapshot accepts regular files only")
            if (not isinstance(path, str) or not path or path.startswith("/") or "\\" in path
                    or any(part in ("", ".", "..") for part in path.split("/"))):
                raise GitHubError(400, "Unsafe repository path")
            blob_sha = item.get("sha")
            if not isinstance(blob_sha, str) or not blob_sha:
                raise GitHubError(400, "Snapshot blob SHA missing")
            blob = self.request("GET", root + f"/git/blobs/{blob_sha}")
            if blob.get("encoding") != "base64" or not isinstance(blob.get("content"), str):
                raise GitHubError(400, "Sandbox snapshot requires text blobs")
            try:
                raw = base64.b64decode("".join(blob["content"].split()), validate=True)
                content = raw.decode("utf-8")
            except (binascii.Error, UnicodeError) as exc:
                raise GitHubError(400, "Sandbox snapshot requires UTF-8 files") from exc
            used += len(raw)
            if used > SNAPSHOT_LIMIT:
                raise GitHubError(413, "Repository snapshot exceeds sandbox limits")
            files.append({"path": path, "content": content})
        return {"repo": f"{self.org}/{repo}", "sha": sha, "files": files}

    def comment(self, repo: str, number: int, body: str) -> dict:
        self.request("POST", f"/repos/{self.org}/{repo}/issues/{number}/comments", {"body": body})
        return {"repo": f"{self.org}/{repo}", "number": number}

    def review_status(self, repo: str, number: int, sha: str, job_id: str) -> dict:
        """Report human approval only for the immutable candidate commit and protected main."""
        if not re.fullmatch(r"[0-9a-f]{40}", sha):
            raise ValueError("sha")
        root = f"/repos/{self.org}/{repo}"
        metadata = self.request("GET", root)
        if metadata.get("description") != f"keel-devflow:{job_id}" or metadata.get("private") is not True:
            raise GitHubError(403, "Repository is not the private repository for this task")
        pull = self.request("GET", root + f"/pulls/{number}")
        if (pull.get("head", {}).get("sha") != sha or pull.get("base", {}).get("ref") != "main"
                or pull.get("state") != "open"):
            raise GitHubError(409, "PR head, base or state changed; review evidence is stale")
        try:
            protection = self.request("GET", root + "/branches/main/protection")
            required = protection.get("required_pull_request_reviews", {}).get("required_approving_review_count", 0)
        except GitHubError as exc:
            if exc.status != 403:
                raise
            required = 0
        reviews = self.request("GET", root + f"/pulls/{number}/reviews?per_page=100")
        if not isinstance(reviews, list) or len(reviews) >= 100:
            raise GitHubError(409, "Review history is incomplete")
        latest = {}
        for index, review in enumerate(reviews):
            user = review.get("user") or {}
            login = user.get("login")
            if user.get("type") == "User" and isinstance(login, str) and login:
                order = review.get("id") if isinstance(review.get("id"), int) else index
                if login not in latest or order > latest[login][0]:
                    latest[login] = (order, review)
        reviewers = sorted(login for login, (_, review) in latest.items()
                           if review.get("state") == "APPROVED" and review.get("commit_id") == sha)
        return {"repo": f"{self.org}/{repo}", "number": number, "headSha": sha,
                "protection": "ENFORCED" if required >= 1 else "UNAVAILABLE",
                "ready": not pull.get("draft", True), "reviewers": reviewers,
                "approved": required >= 1 and not pull.get("draft", True) and len(reviewers) >= required}

    def merge(self, repo: str, number: int, *, job_id: str | None = None,
              expected_sha: str | None = None) -> dict:
        repository = self.request("GET", f"/repos/{self.org}/{repo}")
        if str(repository.get("description") or "").startswith("keel-devflow:"):
            if (not job_id or repository.get("description") != f"keel-devflow:{job_id}"
                    or not isinstance(expected_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", expected_sha)):
                raise GitHubError(403, "Produced employees require a job and exact reviewed commit")
            pull = self.request("GET", f"/repos/{self.org}/{repo}/pulls/{number}")
            if pull.get("head", {}).get("sha") != expected_sha:
                raise GitHubError(409, "Reviewed candidate head changed")
            if pull.get("merged") is True:
                merged_sha = str(pull.get("merge_commit_sha") or "")
                if not re.fullmatch(r"[0-9a-f]{40}", merged_sha):
                    raise GitHubError(409, "Merged commit SHA missing")
                return {"repo": f"{self.org}/{repo}", "number": number, "sha": merged_sha,
                        "headSha": expected_sha, "merged": True}
            review = self.review_status(repo, number, expected_sha, job_id)
            if not review["approved"]:
                raise GitHubError(403, "Protected human review is required before merge")
            merged = self.request("PUT", f"/repos/{self.org}/{repo}/pulls/{number}/merge",
                                  {"sha": expected_sha})
            if merged.get("merged") is not True or not re.fullmatch(r"[0-9a-f]{40}", str(merged.get("sha") or "")):
                raise GitHubError(409, "GitHub did not confirm the reviewed merge")
            return {"repo": f"{self.org}/{repo}", "number": number, "sha": merged["sha"],
                    "headSha": expected_sha, "merged": True}
        merged = self.request("PUT", f"/repos/{self.org}/{repo}/pulls/{number}/merge", {})
        return {"repo": f"{self.org}/{repo}", "number": number, "sha": merged.get("sha", "")}

    def dispatch(self, repo: str, workflow: str, ref: str, inputs: dict | None) -> dict:
        self.request("POST", f"/repos/{self.org}/{repo}/actions/workflows/{workflow}/dispatches",
                     {"ref": ref, "inputs": inputs or {}})
        return {"repo": f"{self.org}/{repo}", "workflow": workflow, "ref": ref}

    def logs(self, repo: str, run_id: str) -> dict:
        root = f"/repos/{self.org}/{repo}/actions/runs/{run_id}"
        try:
            content = self._download_logs(root + "/logs")
        except GitHubError:
            jobs = self.request("GET", root + "/jobs?per_page=100", timeout=10)
            lines = []
            for job in jobs.get("jobs", [])[:100]:
                lines.append(f"Job {job.get('name', '')}: {job.get('conclusion') or job.get('status', 'unknown')}")
                for step in job.get("steps", [])[:100]:
                    if step.get("conclusion") == "failure":
                        lines.append(f"  failed step: {step.get('name', '')}")
            if not lines:
                raise GitHubError(502, "CI logs and job evidence unavailable")
            text = "\n".join(lines)[:LOG_LIMIT]
            return {"repo": f"{self.org}/{repo}", "runId": run_id, "log": text,
                    "truncated": False, "logsUnavailable": True}
        if isinstance(content, str):
            text = content
        elif zipfile.is_zipfile(io.BytesIO(content)):
            parts = []
            remaining = LOG_LIMIT + 1
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                for member in sorted(archive.infolist(), key=lambda item: item.filename):
                    if member.is_dir() or remaining <= 0:
                        continue
                    with archive.open(member) as source:
                        chunk = source.read(remaining)
                    part = chunk.decode("utf-8", errors="replace")
                    parts.append(f"[{member.filename}]\n{part}")
                    remaining -= len(chunk)
            text = "\n".join(parts)
        else:
            text = content.decode("utf-8", errors="replace")
        truncated = len(text) > LOG_LIMIT
        return {"repo": f"{self.org}/{repo}", "runId": run_id,
                "log": text[:LOG_LIMIT], "truncated": truncated}
