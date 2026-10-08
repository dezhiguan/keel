"""Run seed cases against /v1/invoke and score them with evals/scorers.py."""

import importlib.util
import json
from collections.abc import Callable
from pathlib import Path
from typing import Any

import httpx

Scorer = Callable[[Any, str, list[str]], float]


def load_scorers(path: str | Path) -> list[Scorer]:
    file = Path(path)
    if not file.is_file():
        raise FileNotFoundError(f"找不到评分函数 {file}")
    spec = importlib.util.spec_from_file_location("keel_eval_scorers", file)
    if spec is None or spec.loader is None:
        raise ValueError(f"无法加载 {file}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    found = []
    for name, fn in vars(module).items():
        if name.startswith("_") or not callable(fn) or getattr(fn, "__module__", None) != module.__name__:
            continue
        found.append(fn)
    if not found:
        raise ValueError(f"{file} 里没有评分函数")
    return found


def invoke_text(endpoint: str, text: str, *, eval_run_id: str, env: str) -> str:
    url = endpoint.rstrip("/") + "/v1/invoke"
    headers = {"X-Keel-Eval-Run": eval_run_id, "X-Keel-Env": env, "Accept": "text/event-stream"}
    with httpx.Client(timeout=60) as client:
        with client.stream("POST", url, json={"input": {"text": text}}, headers=headers) as response:
            if response.status_code >= 400:
                raise ValueError(f"调用失败：{response.status_code} {response.read().decode('utf-8', 'replace')}")
            return _final_answer(response.iter_lines())


def score_by_tag(rows: list[dict[str, Any]], scorers: list[Scorer], answers: list[str]) -> dict[str, float]:
    buckets: dict[str, list[float]] = {}
    for row, answer in zip(rows, answers, strict=True):
        tags = row.get("tags") or ["untagged"]
        if not isinstance(tags, list) or not tags:
            tags = ["untagged"]
        expected = row.get("expected", row.get("expectedOutput"))
        values = [float(fn(expected, answer, tags)) for fn in scorers]
        score = sum(values) / len(values)
        for tag in tags:
            buckets.setdefault(str(tag), []).append(score)
    return {tag: sum(values) / len(values) for tag, values in buckets.items()}


def _final_answer(lines) -> str:
    event = ""
    data: list[str] = []

    def flush() -> str | None:
        nonlocal event, data
        if not data:
            event = ""
            return None
        payload = json.loads("\n".join(data))
        name, data, event = event, [], ""
        if name == "error":
            raise ValueError(payload.get("message") or payload.get("code") or "invoke error")
        if name == "final":
            return str(payload.get("answer", ""))
        return None

    for raw in lines:
        line = raw.decode("utf-8") if isinstance(raw, bytes) else raw
        if line == "":
            found = flush()
            if found is not None:
                return found
            continue
        if line.startswith("event:"):
            event = line.split(":", 1)[1].strip()
        elif line.startswith("data:"):
            data.append(line.split(":", 1)[1].strip())
    found = flush()
    if found is None:
        raise ValueError("调用没有返回 final 事件")
    return found
