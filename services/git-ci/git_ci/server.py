"""HTTP tools. Token and API root come from the environment and are never defaulted."""

import json
import os
import re
import uuid
import hmac

from starlette.applications import Starlette
from starlette.concurrency import run_in_threadpool
from starlette.requests import Request
from starlette.responses import JSONResponse
from starlette.routing import Route

from keel._generated.errors import ErrorCode
from git_ci.github import GitHub, GitHubError

_NAME = re.compile(r"^[a-z][a-z0-9-]{0,62}$")
_REF = re.compile(r"^[A-Za-z0-9._/-]{1,128}$")


def client_from_env() -> GitHub:
    token = os.environ.get("KEEL_GITHUB_TOKEN", "").strip()
    api = os.environ.get("KEEL_GITHUB_API", "").strip()
    if not token or not api:
        raise ValueError("KEEL_GITHUB_TOKEN 或 KEEL_GITHUB_API 未设置")
    org = os.environ.get("KEEL_GITHUB_ORG", "keel-agents").strip()
    if not _NAME.fullmatch(org):
        raise ValueError("KEEL_GITHUB_ORG 无效")
    return GitHub(api, token, org=org, server_url=os.environ.get("KEEL_SERVER_URL", ""),
                  log_archive_proxy=os.environ.get("KEEL_GITHUB_LOG_ARCHIVE_PROXY", ""),
                  devflow_visibility=os.environ.get("KEEL_DEVFLOW_REPO_VISIBILITY", "private").strip())


def call_tool(github: GitHub, tool: str, body: dict) -> dict:
    if tool == "git.repo.snapshot":
        sha = body.get("sha")
        if not isinstance(sha, str):
            raise ValueError("sha")
        return github.snapshot(_name(body.get("repo"), "repo"), sha, _job(body.get("jobId")))
    if tool == "git.repo.create":
        if body.get("jobId"):
            return github.ensure_repo(_name(body.get("name"), "name"), _job(body["jobId"]))
        return github.create_repo(_name(body.get("name"), "name"))
    if tool == "git.branch.push":
        files = body.get("files")
        if not isinstance(files, list) or not files:
            raise ValueError("files")
        cleaned = []
        for item in files:
            if not isinstance(item, dict):
                raise ValueError("file")
            path = item.get("path")
            content = item.get("content")
            if not isinstance(path, str) or not isinstance(content, str) or ".." in path.split("/"):
                raise ValueError("file")
            cleaned.append({"path": path, "content": content})
        if body.get("jobId"):
            expected = body.get("expectedSha")
            if expected is not None and (not isinstance(expected, str) or not re.fullmatch(r"[0-9a-f]{40}", expected)):
                raise ValueError("expectedSha")
            return github.push_scaffold(_name(body.get("repo"), "repo"), _job(body["jobId"]),
                                        _text(body.get("message")), cleaned, expected_sha=expected)
        return github.push(_name(body.get("repo"), "repo"), _ref(body.get("branch")),
                           _text(body.get("message")), cleaned)
    if tool == "git.pr.open":
        if body.get("jobId"):
            return github.ensure_pr(_name(body.get("repo"), "repo"), _job(body["jobId"]), _text(body.get("title")), _text(body.get("body")))
        return github.open_pr(_name(body.get("repo"), "repo"), _ref(body.get("head")), _ref(body.get("base")),
                              _text(body.get("title")), _text(body.get("body")))
    if tool == "git.pr.diff":
        return github.diff(_name(body.get("repo"), "repo"), _number(body.get("number")))
    if tool == "git.pr.review.status":
        sha = body.get("sha")
        if not isinstance(sha, str):
            raise ValueError("sha")
        return github.review_status(_name(body.get("repo"), "repo"), _number(body.get("number")),
                                    sha, _job(body.get("jobId")))
    if tool == "git.pr.comment":
        return github.comment(_name(body.get("repo"), "repo"), _number(body.get("number")), _text(body.get("body")))
    if tool == "git.pr.merge":
        job_id = _job(body["jobId"]) if body.get("jobId") is not None else None
        expected_sha = body.get("expectedSha")
        if expected_sha is not None and (not isinstance(expected_sha, str) or not re.fullmatch(r"[0-9a-f]{40}", expected_sha)):
            raise ValueError("expectedSha")
        return github.merge(_name(body.get("repo"), "repo"), _number(body.get("number")),
                            job_id=job_id, expected_sha=expected_sha)
    if tool == "ci.workflow.dispatch":
        inputs = body.get("inputs") or {}
        if not isinstance(inputs, dict):
            raise ValueError("inputs")
        return github.dispatch(_name(body.get("repo"), "repo"), _ref(body.get("workflow")),
                               _ref(body.get("ref")), inputs)
    if tool == "ci.log.fetch":
        return github.logs(_name(body.get("repo"), "repo"), _text(body.get("runId")))
    raise ValueError("tool")


def create_app(github: GitHub, token: str | None = None, merge_token: str | None = None) -> Starlette:
    async def invoke(request: Request):
        if token is not None and not hmac.compare_digest(request.headers.get("authorization", ""), "Bearer " + token):
            return _error(ErrorCode.AUTH_UNAUTHENTICATED)
        try:
            body = await request.json()
        except json.JSONDecodeError:
            return _error(ErrorCode.SERVER_INVALID_PARAM)
        if not isinstance(body, dict):
            return _error(ErrorCode.SERVER_INVALID_PARAM)
        if request.path_params["tool"] == "git.pr.merge" and body.get("jobId"):
            if not merge_token or not hmac.compare_digest(
                    request.headers.get("x-keel-merge-token", ""), merge_token):
                return _error(ErrorCode.AUTH_UNAUTHENTICATED)
        try:
            result = await run_in_threadpool(call_tool, github, request.path_params["tool"], body)
            return JSONResponse(result)
        except ValueError:
            return _error(ErrorCode.SERVER_INVALID_PARAM)
        except GitHubError:
            return _error(ErrorCode.GIT_UPSTREAM_FAILED)

    async def health(request: Request):
        return JSONResponse({"status": "ok", "service": "git-ci"})
    return Starlette(routes=[Route("/v1/tools/{tool}", invoke, methods=["POST"]), Route("/v1/health", health)])


def app():
    token = os.environ.get("KEEL_GIT_CI_TOKEN", "").strip()
    if not token:
        raise ValueError("KEEL_GIT_CI_TOKEN 未设置")
    return create_app(client_from_env(), token, os.environ.get("KEEL_GIT_CI_MERGE_TOKEN", "").strip())


def _name(value, _label: str) -> str:
    if not isinstance(value, str) or not _NAME.fullmatch(value):
        raise ValueError(_label)
    return value


def _ref(value) -> str:
    if not isinstance(value, str) or not _REF.fullmatch(value) or value.startswith("/"):
        raise ValueError("ref")
    return value


def _text(value) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ValueError("text")
    return value


def _number(value) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
        raise ValueError("number")
    return value


def _error(code: ErrorCode) -> JSONResponse:
    return JSONResponse(
        {"code": code.value, "message": code.message, "trace_id": uuid.uuid4().hex, "retryable": code.retryable},
        status_code=code.http)


def _job(value) -> str:
    if not isinstance(value, str) or not re.fullmatch(r"DF-[0-9]{4,12}", value):
        raise ValueError("jobId")
    return value
