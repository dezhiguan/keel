"""Bounded local trace batches; failed HTTP batches are replayed in order."""

import base64
import json
import logging
import os
import threading
from pathlib import Path
from typing import Callable

logger = logging.getLogger(__name__)


class TraceBuffer:
    def __init__(self, path: str | Path, max_entries: int = 1000, max_bytes: int = 10_000_000):
        if max_entries < 1 or max_bytes < 1:
            raise ValueError("Trace buffer limits must be positive")
        self.path = Path(path)
        self.max_entries = max_entries
        self.max_bytes = max_bytes
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None

    def _read(self) -> list[str]:
        if not self.path.exists():
            return []
        return self.path.read_text(encoding="utf-8").splitlines()

    def _write(self, lines: list[str]) -> None:
        self.path.parent.mkdir(parents=True, exist_ok=True)
        temporary = self.path.with_suffix(self.path.suffix + ".tmp")
        with temporary.open("w", encoding="utf-8") as file:
            if lines:
                file.write("\n".join(lines) + "\n")
            file.flush()
            os.fsync(file.fileno())
        temporary.replace(self.path)

    def append(self, payload: bytes, *, trace_id: str = "", agent: str = "") -> None:
        line = json.dumps({"payload": base64.b64encode(payload).decode("ascii")}, separators=(",", ":"))
        with self._lock:
            lines = self._read()
            lines.append(line)
            dropped = 0
            while len(lines) > self.max_entries or sum(len(item) + 1 for item in lines) > self.max_bytes:
                lines.pop(0)
                dropped += 1
            self._write(lines)
        if dropped:
            logger.warning("trace buffer dropped oldest batches trace_id=%s agent=%s count=%d",
                           trace_id, agent, dropped)

    def replay(self, send: Callable[[bytes], bool]) -> int:
        sent = 0
        with self._lock:
            lines = self._read()
            while lines:
                payload = base64.b64decode(json.loads(lines[0])["payload"])
                if not send(payload):
                    break
                # TODO(P0-7): confirm Langfuse deduplicates span IDs if a crash happens
                # after HTTP success but before this local acknowledgement is persisted.
                lines.pop(0)
                self._write(lines)
                sent += 1
        return sent

    def pending(self) -> int:
        with self._lock:
            return len(self._read())

    def start(self, send: Callable[[bytes], bool], interval_seconds: float = 5) -> None:
        if self._thread is not None:
            return

        def loop():
            while not self._stop.wait(interval_seconds):
                try:
                    self.replay(send)
                except Exception:
                    logger.warning("trace replay failed trace_id= agent=")

        self._thread = threading.Thread(target=loop, name="keel-trace-replay", daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=2)
