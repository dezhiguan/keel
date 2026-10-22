"""Local session memory. Conversation text remains in the local keel-lite database."""

import time
from collections.abc import Callable

from keel.protocol.errors import ErrorCode, KeelError

from .store import LiteStore


class LiteMemory:
    def __init__(self, store: LiteStore | None, agent: str, session_id: str | None,
                 eval_run_id: str | None = None, ttl_seconds: int = 7 * 86400,
                 clock: Callable[[], float] = time.time, guard=None):
        self.store = store
        self.agent = agent
        self.session_id = session_id
        self.eval_run_id = eval_run_id
        self.ttl_seconds = ttl_seconds
        self.clock = clock
        self._user = None
        self.guard = guard

    def bind_user(self, remote) -> None:
        self._user = remote

    async def remember(self, owner: str, content: str):
        if self.guard is not None and self.guard.tainted:
            return None
        if self._user is None:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "用户记忆未配置")
        return await self._user.remember(owner, content)

    def recall(self, owner: str):
        if self._user is None:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "用户记忆未配置")
        return self._user.recall(owner)

    def forget(self, memory_id: int):
        if self._user is None:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "用户记忆未配置")
        return self._user.forget(memory_id)

    def history(self) -> list[dict]:
        if not self.session_id:
            return []
        assert self.store is not None
        return self.store.session_messages(self.agent, self.session_id, self.clock())

    def context_history(self):
        if not self.session_id:
            return []
        return self.store.session_messages(self.agent, self.session_id, self.clock(), include_ids=True)

    def local_summary(self):
        return self.store.session_summary(self.agent, self.session_id, self.clock())

    def save_local_summary(self, state):
        if not self.eval_run_id and not (self.guard and self.guard.tainted):
            self.store.save_session_summary(self.agent, self.session_id, state, self.clock(), self.ttl_seconds)
            return self.local_summary()

    async def load_summary(self, owner):
        return await self._user.load_summary(owner, self.session_id)

    async def save_summary(self, owner, state):
        if not self.eval_run_id and not (self.guard and self.guard.tainted):
            return await self._user.save_summary(owner, self.session_id, state)

    def append(self, role: str, content: str) -> None:
        if self.guard is not None and self.guard.tainted:
            return
        if self.eval_run_id:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "评测流量禁止写入会话记忆")
        if not self.session_id:
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "缺少 session_id")
        if role not in ("user", "assistant") or not isinstance(content, str):
            raise KeelError(ErrorCode.SERVER_INVALID_PARAM, "会话记忆只接受用户与助手文本")
        assert self.store is not None
        self.store.append_session_message(self.agent, self.session_id, role, content,
                                          self.clock(), self.ttl_seconds)
