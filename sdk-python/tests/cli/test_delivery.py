import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

import pytest

from keel.cli import main
from keel.cli.new import create_project


class _Server(ThreadingHTTPServer):
    def __init__(self, handler):
        super().__init__(("127.0.0.1", 0), handler)
        self.requests = []


def _serve(handler):
    server = _Server(handler)
    Thread(target=server.serve_forever, daemon=True).start()
    return server


def _project(tmp_path, monkeypatch):
    project = create_project("hello-agent", root=tmp_path)
    (project / "evals" / "seed.jsonl").write_text(
        '{"input":"ping","expected":"ping","tags":["read"]}\n', encoding="utf-8")
    (project / "evals" / "scorers.py").write_text(
        "def exact(expected, actual, tags):\n    return 1.0 if actual == expected else 0.0\n", encoding="utf-8")
    monkeypatch.chdir(project)
    return project


def test_register_posts_the_manifest_and_env(tmp_path, monkeypatch):
    _project(tmp_path, monkeypatch)

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length))
            self.server.requests.append((self.path, body))
            raw = json.dumps({"code": "OK", "data": {"passed": True}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt, *args):
            return

    server = _serve(Handler)
    monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{server.server_port}")
    main(["register", "--env", "staging"])
    path, body = server.requests[0]
    assert path == "/api/v1/agents"
    assert body["apiVersion"] == "keel/v1"
    assert body["metadata"]["name"] == "hello-agent"
    assert body["env"] == "staging"
    server.shutdown()


def test_import_uses_the_dataset_endpoints(tmp_path, monkeypatch):
    project = _project(tmp_path, monkeypatch)
    raw = (project / "agent.yaml").read_text(encoding="utf-8")
    (project / "agent.yaml").write_text(raw + "  eval:\n    dataset: hello-agent/smoke\n", encoding="utf-8")

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length))
            self.server.requests.append(("POST", self.path, body))
            raw = b"{}"
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            self.server.requests.append(("GET", self.path, None))
            raw = b'{"data":[]}'
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt, *args):
            return

    server = _serve(Handler)
    monkeypatch.setenv("LANGFUSE_HOST", f"http://127.0.0.1:{server.server_port}")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "pk")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "sk")
    main(["eval", "import"])
    posts = [item for item in server.requests if item[0] == "POST"]
    assert posts[0][1] == "/api/public/datasets"
    assert posts[0][2] == {"name": "hello-agent/smoke"}
    assert posts[1][1] == "/api/public/dataset-items"
    assert posts[1][2]["datasetName"] == "hello-agent/smoke"
    assert posts[1][2]["input"] == "ping"
    server.shutdown()


def test_gate_stops_before_the_report_when_a_tag_regresses(tmp_path, monkeypatch):
    project = _project(tmp_path, monkeypatch)
    (project / "baseline.json").write_text('{"read": 1.0}', encoding="utf-8")

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            self.rfile.read(length)
            raw = b'event: final\ndata: {"answer":"nope","trace_id":"ab","run_id":"cd"}\n\n'
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt, *args):
            return

    server = _serve(Handler)
    with pytest.raises(SystemExit) as caught:
        main(["gate", "--env", "prod", "--endpoint", f"http://127.0.0.1:{server.server_port}", "--baseline", "baseline.json"])
    assert caught.value.code == 1
    assert not (project / ".keel" / "gate-run-id").exists()
    server.shutdown()


def test_passing_gate_is_what_release_sends(tmp_path, monkeypatch):
    project = _project(tmp_path, monkeypatch)
    seen = []

    class Agent(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            self.rfile.read(length)
            assert self.headers["X-Keel-Eval-Run"]
            assert self.headers["X-Keel-Env"] == "prod"
            raw = b'event: final\ndata: {"answer":"ping","trace_id":"ab","run_id":"cd"}\n\n'
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt, *args):
            return

    class Keel(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            body = json.loads(self.rfile.read(length))
            seen.append((self.path, body))
            raw = json.dumps({"code": "OK", "data": {"version": "v0"}}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt, *args):
            return

    agent = _serve(Agent)
    keel = _serve(Keel)
    monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{keel.server_port}")
    main(["gate", "--env", "prod", "--endpoint", f"http://127.0.0.1:{agent.server_port}"])
    run_id = (project / ".keel" / "gate-run-id").read_text(encoding="utf-8")
    assert seen[0][0] == "/api/v1/gate-results"
    assert seen[0][1]["passed"] is True
    assert seen[0][1]["gateRunId"] == run_id
    assert "promptVersions" not in seen[0][1]
    main(["release", "--env", "prod", "--image", "hello:1"])
    assert seen[1][0] == "/api/v1/agents/hello-agent/releases"
    assert seen[1][1] == {"env": "prod", "gateRunId": run_id, "image": "hello:1"}
    agent.shutdown()
    keel.shutdown()
