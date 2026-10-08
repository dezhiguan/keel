"""HTTP tools. Token and API root come from the environment and are never defaulted."""

import json
import os
import re
import uuid

from starlette.applications import Starlette
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
    return GitHub(api, token)


def call_tool(github: GitHub, tool: str, body: dict) -> dict:
    if tool == "git.repo.create":
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
        return github.push(_name(body.get("repo"), "repo"), _ref(body.get("branch")),
                           _text(body.get("message")), cleaned)
    if tool == "git.pr.open":
        return github.open_pr(_name(body.get("repo"), "repo"), _ref(body.get("head")), _ref(body.get("base")),
                              _text(body.get("title")), _text(body.get("body")))
    if tool == "git.pr.diff":
        return github.diff(_name(body.get("repo"), "repo"), _number(body.get("number")))
    if tool == "git.pr.comment":
        return github.comment(_name(body.get("repo"), "repo"), _number(body.get("number")), _text(body.get("body")))
    if tool == "git.pr.merge":
        return github.merge(_name(body.get("repo"), "repo"), _number(body.get("number")))
    if tool == "ci.workflow.dispatch":
        inputs = body.get("inputs") or {}
        if not isinstance(inputs, dict):
            raise ValueError("inputs")
        return github.dispatch(_name(body.get("repo"), "repo"), _ref(body.get("workflow")),
                               _ref(body.get("ref")), inputs)
    if tool == "ci.log.fetch":
        return github.logs(_name(body.get("repo"), "repo"), _text(body.get("runId")))
    raise ValueError("tool")


def create_app(github: GitHub) -> Starlette:
    async def invoke(request: Request):
        try:
            body = await request.json()
        except json.JSONDecodeError:
            return _error(ErrorCode.SERVER_INVALID_PARAM)
        if not isinstance(body, dict):
            return _error(ErrorCode.SERVER_INVALID_PARAM)
        try:
            return JSONResponse(call_tool(github, request.path_params["tool"], body))
        except ValueError:
            return _error(ErrorCode.SERVER_INVALID_PARAM)
        except GitHubError:
            return _error(ErrorCode.GIT_UPSTREAM_FAILED)

    return Starlette(routes=[Route("/v1/tools/{tool}", invoke, methods=["POST"])])


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
