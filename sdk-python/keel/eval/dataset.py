"""Dataset import using the two Langfuse calls already used by keel-server."""

import hashlib
import json
import os
from pathlib import Path
from typing import Any

import httpx


def load_seed(path: str | Path) -> list[dict[str, Any]]:
    file = Path(path)
    if not file.is_file():
        raise FileNotFoundError(f"找不到评测用例 {file}")
    rows = []
    for line in file.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        item = json.loads(line)
        if not isinstance(item, dict):
            raise ValueError("评测用例必须是 JSON 对象")
        rows.append(item)
    if not rows:
        raise ValueError("评测用例是空的")
    return rows


def import_dataset(dataset: str, rows: list[dict[str, Any]], *, lines: list[str]) -> int:
    host = os.environ.get("LANGFUSE_HOST", "").strip().rstrip("/")
    public = os.environ.get("LANGFUSE_PUBLIC_KEY", "").strip()
    secret = os.environ.get("LANGFUSE_SECRET_KEY", "").strip()
    if not host or not public or not secret:
        raise ValueError("LANGFUSE_HOST、LANGFUSE_PUBLIC_KEY、LANGFUSE_SECRET_KEY 都要设置")
    auth = (public, secret)
    with httpx.Client(timeout=30) as client:
        created = client.post(f"{host}/api/public/datasets", json={"name": dataset}, auth=auth)
        if created.status_code >= 400:
            raise ValueError(f"创建数据集失败：{created.status_code} {created.text}")
        listed = client.get(f"{host}/api/public/dataset-items", params={"datasetName": dataset}, auth=auth)
        if listed.status_code >= 400:
            raise ValueError(f"读取数据集失败：{listed.status_code} {listed.text}")
        existing = {item.get("id") for item in listed.json().get("data", []) if isinstance(item, dict)}
        added = 0
        for line, row in zip(lines, rows, strict=True):
            item_id = str(row.get("id") or hashlib.sha256(line.encode("utf-8")).hexdigest())
            if item_id in existing:
                continue
            body: dict[str, Any] = {"id": item_id, "datasetName": dataset}
            if "input" in row:
                body["input"] = row["input"]
            expected = row.get("expectedOutput", row.get("expected"))
            if expected is not None:
                body["expectedOutput"] = expected
            posted = client.post(f"{host}/api/public/dataset-items", json=body, auth=auth)
            if posted.status_code >= 400:
                raise ValueError(f"写入用例失败：{posted.status_code} {posted.text}")
            existing.add(item_id)
            added += 1
    return added


def seed_lines(path: str | Path) -> list[str]:
    return [line for line in Path(path).read_text(encoding="utf-8").splitlines() if line.strip()]
