import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import Thread

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa

from keel.cli.ledger import open_job
from keel.cli.server import ServerRejected


def _pem() -> str:
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return key.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption(),
    ).decode()


class _Server(ThreadingHTTPServer):
    def __init__(self):
        self.calls = []

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                body = json.loads(self.rfile.read(length) or b"{}")
                self.server.calls.append((self.path, body, self.headers.get("X-Client-Id")))
                if self.path.endswith("/stages/SPEC/report"):
                    data = {"jobId": "DF-0001", "stage": "H1", "status": "WAIT"}
                else:
                    data = {"jobId": "DF-0001", "stage": "SPEC", "status": "RUN"}
                raw = json.dumps({"code": "OK", "data": data}).encode()
                self.send_response(200)
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def log_message(self, _format, *_args):
                return

        super().__init__(("127.0.0.1", 0), Handler)


def test_unset_server_does_not_call(monkeypatch):
    monkeypatch.delenv("KEEL_SERVER_URL", raising=False)
    assert open_job(title="需求分析师", target_agent="spec-agent", goal="起草") is None


def test_missing_assertion_does_not_call(monkeypatch):
    monkeypatch.setenv("KEEL_SERVER_URL", "http://127.0.0.1:9")
    monkeypatch.delenv("KEEL_OAUTH_CLIENT_ID", raising=False)
    monkeypatch.delenv("KEEL_OAUTH_PRIVATE_KEY", raising=False)
    monkeypatch.delenv("KEEL_SERVICE_AUDIENCE", raising=False)
    with pytest.raises(ValueError, match="KEEL_OAUTH_CLIENT_ID"):
        open_job(title="需求分析师", target_agent="spec-agent", goal="起草需求单正文不应上传")


def test_opens_a_job_and_reports_the_spec_stage(monkeypatch):
    server = _Server()
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{server.server_address[1]}")
        monkeypatch.setenv("KEEL_OAUTH_CLIENT_ID", "meta-agent")
        monkeypatch.setenv("KEEL_OAUTH_PRIVATE_KEY", _pem())
        monkeypatch.setenv("KEEL_SERVICE_AUDIENCE", "keel-service")
        opened = open_job(title="需求分析师", target_agent="spec-agent", goal="起草需求单正文")
    finally:
        server.shutdown()
    assert opened == {"jobId": "DF-0001", "stage": "H1", "status": "WAIT"}
    assert [path for path, _, _ in server.calls] == [
        "/api/v1/devflow/jobs",
        "/api/v1/devflow/jobs/DF-0001/stages/SPEC/report",
    ]
    create, report = (body for _, body, _ in server.calls)
    assert create["targetAgent"] == "spec-agent"
    assert create["layer"] == "dev"
    assert "mode" not in create
    assert report["artifact"] == {"kind": "SPEC", "ref": "spec.json", "origin": "AGENT"}
    assert "起草需求单正文" not in json.dumps(report, ensure_ascii=False)
    assert server.calls[0][2] == "meta-agent"


def test_server_rejection_keeps_its_code(monkeypatch):
    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            raw = b'{"code":"DEVFLOW_LINEAGE_FORBIDDEN","message":"cannot","retryable":false}'
            self.send_response(403)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, _format, *_args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        monkeypatch.setenv("KEEL_SERVER_URL", f"http://127.0.0.1:{server.server_address[1]}")
        monkeypatch.setenv("KEEL_OAUTH_CLIENT_ID", "meta-agent")
        monkeypatch.setenv("KEEL_OAUTH_PRIVATE_KEY", _pem())
        monkeypatch.setenv("KEEL_SERVICE_AUDIENCE", "keel-service")
        with pytest.raises(ServerRejected) as caught:
            open_job(title="需求分析师", target_agent="spec-agent", goal="起草")
    finally:
        server.shutdown()
    assert caught.value.code == "DEVFLOW_LINEAGE_FORBIDDEN"
