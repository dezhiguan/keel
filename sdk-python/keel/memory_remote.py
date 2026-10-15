"""User memory on keel-server. Session text stays in keel-lite."""

import os

import httpx

from keel.protocol.errors import ErrorCode, KeelError


class UserMemory:
    def __init__(self, agent: str, env: str | None, eval_run_id: str | None = None):
        self.agent = agent
        self.env = env
        self.eval_run_id = eval_run_id

    async def remember(self, owner: str, content: str) -> int:
        if self.eval_run_id:
            raise KeelError(ErrorCode.MEMORY_WRITE_DENIED, "评测流量禁止写入记忆")
        if self.agent == "meta-agent":
            raise KeelError(ErrorCode.MEMORY_WRITE_DENIED)
        body = await self._send("POST", "", {"ownerUser": owner, "content": content})
        return int(body["id"])

    async def recall(self, owner: str) -> list[dict]:
        body = await self._send("GET", f"?ownerUser={owner}", None)
        return list(body.get("items") or [])

    async def forget(self, memory_id: int) -> None:
        await self._send("DELETE", f"/{memory_id}", None)

    async def _send(self, method: str, suffix: str, payload: dict | None) -> dict:
        base = os.environ.get("KEEL_SERVER_URL", "").rstrip("/")
        if not base:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "KEEL_SERVER_URL 未配置")
        url = f"{base}/api/v1/agents/{self.agent}/memories{suffix}"
        async with httpx.AsyncClient(timeout=10) as client:
            response = await client.request(method, url, json=payload)
        if response.status_code == 403:
            raise KeelError(ErrorCode.MEMORY_WRITE_DENIED)
        if response.status_code >= 400:
            raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR)
        data = response.json().get("data") or {}
        return data
