"""Local agent and keel-lite runner.

Dev-only values are injected here and printed at startup. The SDK still fails
when those variables are missing; do not move these defaults into the SDK.
"""

import base64
import json
import os
import signal
import subprocess
import sys
import threading
from collections.abc import Mapping
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import uvicorn

from keel.lite.server import LiteServer
from keel.lite.store import LiteStore
from keel.manifest import load_manifest

LANGFUSE_KEYS = ("LANGFUSE_HOST", "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY")
_LOCAL_LANGFUSE_KEY = "local-dev"


def injected_runtime_env(root: str | Path, port: int, environ: Mapping[str, str]) -> dict[str, str]:
    """Values ``keel dev`` fills in so a fresh project can start.

    Only keys absent from ``environ`` are returned. Callers print and apply them.
    """
    root = Path(root).resolve()
    candidates = {
        "KEEL_ENV": "dev",
        "KEEL_AUDIT_URL": f"http://127.0.0.1:{port + 1}",
        "KEEL_AUDIT_TOKEN": "local-dev-token",
        "KEEL_AUDIT_SPOOL_PATH": str(root / ".keel" / "audit-spool.jsonl"),
        "KEEL_TRACE_BUFFER_PATH": str(root / ".keel" / "traces.jsonl"),
        # BatchSpanProcessor reads this. A short delay keeps the local tree visible
        # without putting a default into the SDK exporter.
        "OTEL_BSP_SCHEDULE_DELAY": "200",
    }
    return {key: value for key, value in candidates.items() if not environ.get(key)}


def langfuse_mode(environ: Mapping[str, str], *, no_trace: bool) -> str:
    if no_trace:
        return "off"
    if all(environ.get(key) for key in LANGFUSE_KEYS):
        return "remote"
    return "local"


def format_span_tree(payload: bytes) -> str:
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    request = ExportTraceServiceRequest.FromString(payload)
    blocks: list[str] = []
    for resource in request.resource_spans:
        for scope in resource.scope_spans:
            spans = list(scope.spans)
            if not spans:
                continue
            known = {span.span_id for span in spans}
            children: dict[bytes, list] = {}
            roots = []
            for span in spans:
                if span.parent_span_id and span.parent_span_id in known:
                    children.setdefault(span.parent_span_id, []).append(span)
                else:
                    roots.append(span)
            lines: list[str] = []

            def walk(span, depth: int) -> None:
                lines.append(f"{'  ' * depth}- {span.name}")
                for child in children.get(span.span_id, ()):
                    walk(child, depth + 1)

            for root in roots:
                walk(root, 0)
            blocks.append("\n".join(lines))
    return "\n\n".join(block for block in blocks if block)


def _append_trace(path: Path, payload: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps({"payload": base64.b64encode(payload).decode("ascii")}, separators=(",", ":"))
    with path.open("a", encoding="utf-8") as file:
        file.write(line + "\n")
        file.flush()
        os.fsync(file.fileno())


def _print_tree(payload: bytes) -> None:
    tree = format_span_tree(payload)
    if tree:
        print("trace\n" + tree, flush=True)


class _TraceHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", "0"))
        payload = self.rfile.read(length) if length else b""
        if self.path.split("?", 1)[0].rstrip("/") != "/api/public/otel/v1/traces":
            self.send_error(404)
            return
        _append_trace(self.server.trace_path, payload)
        try:
            _print_tree(payload)
        except Exception as exc:
            print(f"local trace payload could not be printed: {exc}", flush=True)
        body = b"{}"
        self.send_response(200)
        self.send_header("content-type", "application/json")
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        return


class LocalTraceSink:
    """Accepts Langfuse OTLP/HTTP so the SDK can export without a dev project."""

    def __init__(self, path: Path):
        self.path = path
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), _TraceHandler)
        self.httpd.trace_path = path
        self.thread = threading.Thread(target=self.httpd.serve_forever, name="keel-local-trace", daemon=True)

    @property
    def url(self) -> str:
        host, port = self.httpd.server_address
        return f"http://{host}:{port}"

    def start(self) -> None:
        self.thread.start()

    def close(self) -> None:
        self.httpd.shutdown()
        self.httpd.server_close()
        self.thread.join(timeout=5)


class TraceFileWatcher:
    """Print a tree when a remote Langfuse export falls back to the local buffer."""

    def __init__(self, path: Path):
        self.path = path
        self._stop = threading.Event()
        self._seen = path.read_text(encoding="utf-8") if path.exists() else ""
        self.thread = threading.Thread(target=self._loop, name="keel-trace-watch", daemon=True)

    def start(self) -> None:
        self.thread.start()

    def close(self) -> None:
        self._stop.set()
        self.thread.join(timeout=2)

    def _loop(self) -> None:
        while not self._stop.wait(0.2):
            if not self.path.exists():
                continue
            text = self.path.read_text(encoding="utf-8")
            if text == self._seen:
                continue
            previous = self._seen.splitlines()
            current = text.splitlines()
            self._seen = text
            if current[:len(previous)] == previous:
                added = current[len(previous):]
            else:
                added = [line for line in current if line not in previous]
            if not added:
                continue
            print(f"Langfuse 连不上，span 已降级写入 {self.path}", flush=True)
            for line in added:
                if not line.strip():
                    continue
                try:
                    payload = base64.b64decode(json.loads(line)["payload"])
                    _print_tree(payload)
                except (KeyError, ValueError, json.JSONDecodeError) as exc:
                    print(f"local trace line could not be printed: {exc}", flush=True)


def _print_langfuse_hint(path: Path) -> None:
    print(
        "************************************************************\n"
        "Langfuse 未配置，追踪已降级到本地。\n"
        f"span 写入 {path}\n"
        "终端会打印调用树。\n"
        "要送到 Langfuse dev 项目，启动前设置：\n"
        "  LANGFUSE_HOST\n"
        "  LANGFUSE_PUBLIC_KEY\n"
        "  LANGFUSE_SECRET_KEY\n"
        "************************************************************",
        flush=True,
    )


def _start_lite(root: Path, manifest, port: int) -> tuple[uvicorn.Server, threading.Thread]:
    capture = manifest.spec.audit.captureFields if manifest.spec.audit else ()
    lite = LiteServer(LiteStore(root), capture)
    config = uvicorn.Config(lite, host="127.0.0.1", port=port, log_level="warning")
    server = uvicorn.Server(config)
    thread = threading.Thread(target=server.run, daemon=True, name="keel-lite")
    thread.start()
    for _ in range(100):
        if server.started or not thread.is_alive():
            break
        threading.Event().wait(0.05)
    if not server.started:
        raise RuntimeError(f"keel-lite failed to listen on 127.0.0.1:{port}")
    return server, thread


def _agent_command(root: Path, port: int) -> list[str]:
    return [
        sys.executable, "-m", "uvicorn", "app:app",
        "--host", "127.0.0.1", "--port", str(port),
        "--reload", "--reload-dir", str(root),
        "--reload-exclude", "*/.keel/*",
        "--log-level", "info",
    ]


def run_dev(*, port: int = 8000, no_trace: bool = False, root: str | Path = ".") -> None:
    root = Path(root).resolve()
    manifest = load_manifest(root / "agent.yaml")
    if not (root / "app.py").is_file():
        raise FileNotFoundError(root / "app.py")
    if not 1 <= port <= 65534:
        raise ValueError("port must leave room for keel-lite on port + 1")

    # The SDK reads the child environment and still fails closed when a required
    # variable is missing. Defaults stay in this CLI process's handoff, not in the SDK.
    child_env = os.environ.copy()
    apply_to_child = injected_runtime_env(root, port, child_env)
    for key, value in apply_to_child.items():
        child_env[key] = value
        print(f"dev injected {key}={value}", flush=True)
    buffer_path = Path(child_env["KEEL_TRACE_BUFFER_PATH"])
    mode = langfuse_mode(child_env, no_trace=no_trace)
    sink: LocalTraceSink | None = None
    watcher: TraceFileWatcher | None = None
    if mode == "off":
        for key in LANGFUSE_KEYS:
            child_env.pop(key, None)
        print("Tracing to Langfuse disabled (--no-trace).", flush=True)
    elif mode == "remote":
        print(f"Tracing to Langfuse at {child_env['LANGFUSE_HOST']}", flush=True)
        print(f"如果连不上，span 会写入 {buffer_path}，并在终端打印调用树。", flush=True)
        watcher = TraceFileWatcher(buffer_path)
        watcher.start()
    else:
        if any(child_env.get(key) for key in LANGFUSE_KEYS):
            print("Langfuse 配置不完整，改为本地追踪。需要同时设置 "
                  "LANGFUSE_HOST、LANGFUSE_PUBLIC_KEY、LANGFUSE_SECRET_KEY。", flush=True)
            for key in LANGFUSE_KEYS:
                child_env.pop(key, None)
        sink = LocalTraceSink(buffer_path)
        sink.start()
        for key, value in {
            "LANGFUSE_HOST": sink.url,
            "LANGFUSE_PUBLIC_KEY": _LOCAL_LANGFUSE_KEY,
            "LANGFUSE_SECRET_KEY": _LOCAL_LANGFUSE_KEY,
        }.items():
            child_env[key] = value
            print(f"dev injected {key}={value}", flush=True)
        _print_langfuse_hint(buffer_path)

    server, thread = _start_lite(root, manifest, port + 1)
    print(f"keel-lite: http://127.0.0.1:{port + 1}/api/v1/approvals", flush=True)
    agent = subprocess.Popen(_agent_command(root, port), cwd=root, env=child_env)
    try:
        code = agent.wait()
        if code:
            raise SystemExit(code)
    finally:
        if agent.poll() is None:
            agent.send_signal(signal.SIGINT)
            try:
                agent.wait(timeout=5)
            except subprocess.TimeoutExpired:
                agent.kill()
                agent.wait(timeout=5)
        server.should_exit = True
        thread.join(timeout=5)
        if sink is not None:
            sink.close()
        if watcher is not None:
            watcher.close()
