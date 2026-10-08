"""Shared tool catalog.

TODO(DF-2 / D0-4): read from keel-server with a service identity. GET /api/v1/tools only accepts a
console session today, so M1 reads an optional snapshot exported from the console tools page.
"""

import json
import os
from pathlib import Path


def load() -> dict[str, dict] | None:
    """Return {tool name: entry}, or None when no snapshot is configured and tools cannot be checked."""
    path = os.environ.get("META_TOOL_CATALOG")
    if not path:
        return None
    rows = json.loads(Path(path).read_text(encoding="utf-8"))
    return {row["name"]: row for row in rows}
