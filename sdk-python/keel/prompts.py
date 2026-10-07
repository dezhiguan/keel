"""Read the prompt version for this environment from Langfuse, with a local copy as fallback."""

import logging
import os
import re
import time
from pathlib import Path
from urllib.parse import quote

import httpx

from keel.protocol.errors import ErrorCode, KeelError

logger = logging.getLogger(__name__)
_VARIABLE = re.compile(r"\{\{\s*(\w+)\s*\}\}")
_CONFIG_KEYS = ("temperature", "max_tokens", "top_p")
_TTL_SECONDS = 60


class Prompt:
    def __init__(self, name: str, version: int | None, type: str, fallback: bool, config: dict, body):
        self.name = name
        self.version = version
        self.type = type
        self.fallback = fallback
        self.config = config
        self.body = body

    def compile(self, **variables):
        if self.type == "chat":
            messages = []
            for message in self.body or []:
                messages.append({
                    "role": message.get("role") or "user",
                    "content": _fill(message.get("content") or "", variables),
                })
            return messages
        return _fill(self.body if isinstance(self.body, str) else "", variables)


class PromptClient:
    def __init__(self, manifest, env: str | None, root: Path | None = None):
        self.manifest = manifest
        self.env = env
        self.root = root or Path.cwd()
        self._cache: dict[tuple[str, str], tuple[float, Prompt]] = {}

    async def get(self, agent: str, short_name: str, trace_id: str) -> Prompt:
        declared = _declared(self.manifest, short_name)
        if declared is None:
            raise KeelError(ErrorCode.PROMPT_NOT_DECLARED)
        label = _label(self.env, self.manifest)
        full_name = f"{agent}/{short_name}"
        cached = self._cache.get((full_name, label))
        now = time.monotonic()
        if cached and now - cached[0] < _TTL_SECONDS:
            return cached[1]
        try:
            prompt = await self._load(full_name, declared, label, trace_id, agent)
        except KeelError:
            if cached:
                return cached[1]
            raise
        self._cache[(full_name, label)] = (now, prompt)
        return prompt

    async def _load(self, full_name: str, declared: str, label: str, trace_id: str, agent: str) -> Prompt:
        source = _source(self.manifest)
        if source == "local" or os.environ.get("KEEL_LOCAL_PROMPTS") == "1":
            return _local(self.root, full_name, declared, trace_id, agent)
        try:
            remote = await _langfuse(full_name, label)
        except httpx.HTTPStatusError as exc:
            if exc.response.status_code == 404 and self.env in ("dev", "test"):
                logger.warning("prompt label missing, using local copy trace_id=%s agent=%s name=%s",
                               trace_id, agent, full_name)
                return _local(self.root, full_name, declared, trace_id, agent)
            raise KeelError(ErrorCode.PROMPT_UNAVAILABLE) from exc
        except (httpx.HTTPError, OSError) as exc:
            if self.env in ("dev", "test"):
                logger.warning("prompt read failed, using local copy trace_id=%s agent=%s name=%s",
                               trace_id, agent, full_name)
                return _local(self.root, full_name, declared, trace_id, agent)
            raise KeelError(ErrorCode.PROMPT_UNAVAILABLE) from exc
        if remote["type"] != declared:
            raise KeelError(ErrorCode.PROMPT_TYPE_MISMATCH)
        return Prompt(full_name, int(remote["version"]), declared, False, _config(remote.get("config")), remote["prompt"])


def _declared(manifest, short_name: str) -> str | None:
    prompts = getattr(getattr(manifest, "spec", None), "prompts", None)
    for item in (getattr(prompts, "items", None) or []):
        if item.name == short_name:
            return item.type.value if hasattr(item.type, "value") else str(item.type)
    return None


def _source(manifest) -> str:
    prompts = getattr(getattr(manifest, "spec", None), "prompts", None)
    source = getattr(prompts, "source", None)
    return source.value if hasattr(source, "value") else (source or "langfuse")


def _label(env: str | None, manifest) -> str:
    if not env:
        raise KeelError(ErrorCode.PROMPT_UNAVAILABLE, "KEEL_ENV 未配置")
    if env == "prod":
        prompts = getattr(getattr(manifest, "spec", None), "prompts", None)
        label = getattr(prompts, "label", None) if prompts else None
        return label or "production"
    if env in ("dev", "test", "staging"):
        return env
    raise KeelError(ErrorCode.PROMPT_UNAVAILABLE, "KEEL_ENV 未配置")


async def _langfuse(full_name: str, label: str) -> dict:
    host = os.environ.get("LANGFUSE_HOST", "").rstrip("/")
    public = os.environ.get("LANGFUSE_PUBLIC_KEY", "")
    secret = os.environ.get("LANGFUSE_SECRET_KEY", "")
    if not host or not public or not secret:
        raise KeelError(ErrorCode.PROMPT_UNAVAILABLE, "Langfuse 未配置")
    url = f"{host}/api/public/v2/prompts/{quote(full_name, safe='')}?label={quote(label, safe='')}"
    async with httpx.AsyncClient(timeout=10) as client:
        response = await client.get(url, auth=(public, secret))
        response.raise_for_status()
        return response.json()


def _local(root: Path, full_name: str, declared: str, trace_id: str, agent: str) -> Prompt:
    short = full_name.split("/", 1)[1]
    path = root / "prompts" / f"{short}.{'json' if declared == 'chat' else 'md'}"
    if not path.is_file():
        raise KeelError(ErrorCode.PROMPT_UNAVAILABLE)
    logger.warning("using local prompt copy trace_id=%s agent=%s name=%s", trace_id, agent, full_name)
    if declared == "chat":
        import json
        body = json.loads(path.read_text(encoding="utf-8"))
    else:
        body = path.read_text(encoding="utf-8")
    return Prompt(full_name, None, declared, True, {}, body)


def _config(raw) -> dict:
    if not isinstance(raw, dict):
        return {}
    if "model" in raw:
        logger.warning("prompt config.model is ignored; the model comes from the manifest")
    return {key: raw[key] for key in _CONFIG_KEYS if key in raw}


def _fill(text: str, variables: dict) -> str:
    missing = [match.group(1) for match in _VARIABLE.finditer(text) if match.group(1) not in variables]

    def replace(match: re.Match) -> str:
        return str(variables[match.group(1)])

    if missing:
        raise KeelError(ErrorCode.PROMPT_VARIABLE_MISSING)
    return _VARIABLE.sub(replace, text)
