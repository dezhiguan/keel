"""User memory on keel-server. Session text stays in keel-lite."""

import os
from urllib.parse import urlencode

import httpx
from keel.http_pool import new_async_client
from keel.auth.client_assertion import ASSERTION_TYPE, sign_client_assertion

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

    async def load_summary(self, owner, session):
        query = urlencode({"ownerUser": owner, "env": self.env, "sessionId": session})
        return await self._send("GET", "/session-summary?" + query, None)

    async def save_summary(self, owner, session, state):
        if self.eval_run_id:
            raise KeelError(ErrorCode.MEMORY_WRITE_DENIED)
        return await self._send("POST", "/session-summary", {**state, "ownerUser": owner, "env": self.env, "sessionId": session})

    async def load_facts(self, session):
        params = urlencode({'env': self.env, 'sessionId': session})
        return await self._send('GET', '/session-facts?'+params, None)

    async def save_facts(self, session, state):
        if self.eval_run_id:
            raise KeelError(ErrorCode.MEMORY_WRITE_DENIED)
        return await self._send('POST', '/session-facts', {'env': self.env, 'sessionId': session, 'state': state})

    async def search_session(self, session, query, before):
        return await self._send("POST", "/session-history/search", {"env": self.env,
            "sessionId": session, "query": query, "beforeId": before, "limit": 4})

    async def read_session(self, session, message_id, owner, version, offset=0, limit_chars=1200):
        params = {"env": self.env, "sessionId": session, "offset": offset, "limitChars": limit_chars}
        if version:
            params.update(ownerUser=owner, summaryVersion=version)
        return await self._send("GET", "/session-history/messages/" + message_id + "?" + urlencode(params), None)

    async def _send(self, method: str, suffix: str, payload: dict | None) -> dict:
        base = os.environ.get("KEEL_SERVER_URL", "").rstrip("/")
        if not base:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "KEEL_SERVER_URL 未配置")
        url = f"{base}/api/v1/agents/{self.agent}/memories{suffix}"
        audience = os.environ.get("KEEL_SERVICE_AUDIENCE", "")
        key = os.environ.get("KEEL_CLIENT_PRIVATE_KEY", "")
        if not audience or not key:
            raise KeelError(ErrorCode.AUTH_UNAUTHENTICATED, "记忆服务需要智能体服务身份")
        headers = {"X-Client-Id": self.agent, "X-Client-Assertion-Type": ASSERTION_TYPE,
                   "X-Client-Assertion": sign_client_assertion(self.agent, audience, key)}
        async with new_async_client(timeout=10) as client:
            response = await client.request(method, url, json=payload, headers=headers)
        if response.status_code == 403:
            raise KeelError(ErrorCode.MEMORY_WRITE_DENIED)
        if response.status_code >= 400:
            raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR)
        data = response.json().get("data") or {}
        return data
