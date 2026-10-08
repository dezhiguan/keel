"""Calls keel-server. The base URL comes from KEEL_SERVER_URL and is never defaulted."""

import json
import os
from typing import Any

import httpx


class ServerRejected(Exception):
    def __init__(self, code: str, message: str, status: int) -> None:
        super().__init__(message or code)
        self.code = code
        self.status = status


def server_url() -> str:
    url = os.environ.get("KEEL_SERVER_URL", "").strip()
    if not url:
        raise ValueError("KEEL_SERVER_URL 未设置")
    return url.rstrip("/")


def post_json(path: str, body: dict[str, Any], *, timeout: float = 60) -> dict[str, Any]:
    response = httpx.post(server_url() + path, json=body, timeout=timeout)
    try:
        payload = response.json()
    except json.JSONDecodeError:
        payload = {}
    if response.status_code >= 400 or payload.get("code") not in (None, "OK"):
        code = str(payload.get("code") or response.status_code)
        message = str(payload.get("message") or response.text)
        raise ServerRejected(code, message, response.status_code)
    data = payload.get("data")
    return data if isinstance(data, dict) else {"data": data}
