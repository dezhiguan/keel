"""GitHub REST calls for repositories under keel-agents. No host default."""

import httpx

ORG = "keel-agents"
LOG_LIMIT = 64 * 1024
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
    def __init__(self, base_url: str, token: str, org: str = ORG):
        if not base_url or not token:
            raise ValueError("KEEL_GITHUB_API 或 KEEL_GITHUB_TOKEN 未设置")
        self.base = base_url.rstrip("/")
        self.token = token
        self.org = org

    def request(self, method: str, path: str, payload: dict | None = None, accept: str | None = None,
                raw: bool = False):
        headers = {
            "Authorization": f"Bearer {self.token}",
            "Accept": accept or "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }
        response = httpx.request(method, self.base + path, headers=headers, json=payload, timeout=30)
        if response.status_code >= 300:
            raise GitHubError(response.status_code, response.text)
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
        self.request("POST", f"/orgs/{self.org}/repos", {"name": name, "private": True, "auto_init": True})
        self.request("PUT", f"/repos/{self.org}/{name}/branches/main/protection", PROTECTION)
        return {"repo": f"{self.org}/{name}", "defaultBranch": "main"}

    def push(self, repo: str, branch: str, message: str, files: list[dict]) -> dict:
        parent = self.request("GET", f"/repos/{self.org}/{repo}/git/ref/heads/main")
        commit_sha = parent["object"]["sha"]
        commit = self.request("GET", f"/repos/{self.org}/{repo}/git/commits/{commit_sha}")
        tree = []
        for item in files:
            blob = self.request("POST", f"/repos/{self.org}/{repo}/git/blobs",
                                {"content": item["content"], "encoding": "utf-8"})
            tree.append({"path": item["path"], "mode": "100644", "type": "blob", "sha": blob["sha"]})
        built = self.request("POST", f"/repos/{self.org}/{repo}/git/trees",
                             {"base_tree": commit["tree"]["sha"], "tree": tree})
        created = self.request("POST", f"/repos/{self.org}/{repo}/git/commits",
                               {"message": message, "tree": built["sha"], "parents": [commit_sha]})
        if self.status("GET", f"/repos/{self.org}/{repo}/git/ref/heads/{branch}") == 404:
            self.request("POST", f"/repos/{self.org}/{repo}/git/refs",
                         {"ref": f"refs/heads/{branch}", "sha": created["sha"]})
        else:
            self.request("PATCH", f"/repos/{self.org}/{repo}/git/refs/heads/{branch}", {"sha": created["sha"]})
        return {"repo": f"{self.org}/{repo}", "branch": branch, "sha": created["sha"]}

    def open_pr(self, repo: str, head: str, base: str, title: str, body: str) -> dict:
        opened = self.request("POST", f"/repos/{self.org}/{repo}/pulls",
                              {"head": head, "base": base, "title": title, "body": body})
        return {"repo": f"{self.org}/{repo}", "number": opened["number"]}

    def diff(self, repo: str, number: int) -> dict:
        text = self.request("GET", f"/repos/{self.org}/{repo}/pulls/{number}",
                            accept="application/vnd.github.diff")
        return {"repo": f"{self.org}/{repo}", "number": number, "diff": text}

    def comment(self, repo: str, number: int, body: str) -> dict:
        self.request("POST", f"/repos/{self.org}/{repo}/issues/{number}/comments", {"body": body})
        return {"repo": f"{self.org}/{repo}", "number": number}

    def merge(self, repo: str, number: int) -> dict:
        merged = self.request("PUT", f"/repos/{self.org}/{repo}/pulls/{number}/merge", {})
        return {"repo": f"{self.org}/{repo}", "number": number, "sha": merged.get("sha", "")}

    def dispatch(self, repo: str, workflow: str, ref: str, inputs: dict | None) -> dict:
        self.request("POST", f"/repos/{self.org}/{repo}/actions/workflows/{workflow}/dispatches",
                     {"ref": ref, "inputs": inputs or {}})
        return {"repo": f"{self.org}/{repo}", "workflow": workflow, "ref": ref}

    def logs(self, repo: str, run_id: str) -> dict:
        text = self.request("GET", f"/repos/{self.org}/{repo}/actions/runs/{run_id}/logs", raw=True)
        if not isinstance(text, str):
            text = str(text)
        truncated = len(text) > LOG_LIMIT
        return {"repo": f"{self.org}/{repo}", "runId": run_id,
                "log": text[:LOG_LIMIT], "truncated": truncated}
