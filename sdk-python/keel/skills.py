"""Load a manifest-pinned skill on demand. Loading does not grant its tools."""

import os

import httpx
from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion

from keel.protocol.errors import ErrorCode, KeelError


class Skill:
    def __init__(self, name: str, version: int, instructions: str, tools: list[str], examples: list[str]):
        self.name = name
        self.version = version
        self.instructions = instructions
        self.tools = list(tools)
        self.examples = list(examples)


class SkillClient:
    def __init__(self, manifest, agent: str, env: str | None):
        self.manifest = manifest
        self.agent = agent
        self.env = env

    async def load(self, name: str) -> Skill:
        declared = _declared(self.manifest, name)
        if declared is None:
            raise KeelError(ErrorCode.SKILL_NOT_GRANTED)
        base = os.environ.get("KEEL_SERVER_URL", "").rstrip("/")
        if not base:
            raise KeelError(ErrorCode.SKILL_NOT_GRANTED, "KEEL_SERVER_URL 未配置")
        audience = os.environ.get("KEEL_SERVICE_AUDIENCE", "")
        key = os.environ.get("KEEL_CLIENT_PRIVATE_KEY", "")
        if not audience or not key:
            raise KeelError(ErrorCode.AUTH_UNAUTHENTICATED, "技能服务需要智能体服务身份")
        url = f"{base}/api/v1/agents/{self.agent}/skills/{name}"
        headers = {"X-Keel-Env": self.env or "", "X-Client-Id": self.agent,
                   "X-Client-Assertion-Type": ASSERTION_TYPE,
                   "X-Client-Assertion": sign_client_assertion(self.agent, audience, key)}
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.get(url, headers=headers)
        if response.status_code == 403:
            raise KeelError(ErrorCode.SKILL_NOT_GRANTED)
        if response.status_code >= 400:
            raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR)
        body = response.json().get("data") or {}
        if int(body.get("version") or 0) != declared:
            raise KeelError(ErrorCode.SKILL_NOT_GRANTED)
        return Skill(name, declared, body.get("instructions") or "", list(body.get("tools") or []), list(body.get("examples") or []))


def _declared(manifest, name: str) -> int | None:
    skills = getattr(getattr(manifest, "spec", None), "skills", None) or []
    for item in skills:
        if item.name == name:
            return int(item.version)
    return None
