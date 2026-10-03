"""Agent registration and its public decorators."""

import os
import inspect
from pathlib import Path
from typing import Callable

from keel.manifest import AgentManifest, load_manifest


class Agent:
    _current: "Agent | None" = None

    def __init__(self, manifest: AgentManifest):
        self.manifest = manifest
        self.name = manifest.metadata.name
        self._entry: Callable | None = None
        self._tools: dict[str, Callable] = {}
        Agent._current = self

    @classmethod
    def from_manifest(cls, path: str | Path) -> "Agent":
        return cls(load_manifest(path))

    def entry(self, function: Callable) -> Callable:
        if self._entry is not None:
            raise ValueError("Only one @agent.entry may be registered")
        if not (inspect.iscoroutinefunction(function) or inspect.isasyncgenfunction(function)):
            raise TypeError("@agent.entry requires an async function so disconnect can cancel it")
        self._entry = function
        return function

    def tool(self, function: Callable | None = None, *, name: str | None = None):
        def register(callback: Callable) -> Callable:
            tool_name = name or callback.__name__
            if tool_name in self._tools:
                raise ValueError(f"Tool already registered: {tool_name}")
            self._tools[tool_name] = callback
            return callback
        return register(function) if function is not None else register

    def asgi(self):
        from keel.asgi import create_app
        return create_app(self)

    def mount_to(self, app):
        from keel.asgi import mount_to
        return mount_to(app, self)

    def validate_config(self) -> None:
        required = []
        if self.manifest.spec.models is not None:
            required.extend(("KEEL_LLM_BASE_URL", "KEEL_LLM_KEY"))
        if self.manifest.spec.knowledge:
            required.extend(("KEEL_RAGFORGE_URL", "KEEL_RAGFORGE_TOKEN"))
        if self.manifest.spec.tools or self.manifest.spec.audit:
            required.extend(("KEEL_AUDIT_URL", "KEEL_AUDIT_TOKEN",
                             "KEEL_AUDIT_SPOOL_PATH", "KEEL_ENV"))
        missing = [key for key in required if not os.environ.get(key)]
        if missing:
            raise RuntimeError(f"Missing required environment variable(s): {', '.join(missing)}")
