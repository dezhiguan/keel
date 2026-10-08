import base64
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization

from keel.auth.client_assertion import sign_client_assertion
from keel.auth.delegation import DelegationDenied, issue_delegation_token, issue_from_environment
from keel.protocol.errors import ErrorCode


def _pem():
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    return key.private_bytes(
        serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8,
        serialization.NoEncryption(),
    ).decode()


def test_assertion_is_rs256_and_expires_within_ten_minutes():
    token = sign_client_assertion("meta-agent", "https://auth.example/token", _pem(), now=1_700_000_000)
    header, payload, _signature = token.split(".")
    assert json.loads(_pad(header))["alg"] == "RS256"
    claims = json.loads(_pad(payload))
    assert claims["iss"] == claims["sub"] == "meta-agent"
    assert claims["aud"] == "https://auth.example/token"
    assert claims["exp"] - claims["iat"] <= 600
    with pytest.raises(ValueError):
        sign_client_assertion("meta-agent", "https://auth.example/token", _pem(), ttl_seconds=601)


def test_delegation_posts_the_verified_form():
    seen = {}

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self):
            length = int(self.headers.get("Content-Length", "0"))
            seen["type"] = self.headers.get("Content-Type")
            seen["path"] = self.path
            seen["body"] = self.rfile.read(length).decode()
            raw = json.dumps({"access_token": "agent-token", "expires_in": 600}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, *_args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        token = issue_delegation_token(
            f"http://127.0.0.1:{server.server_address[1]}",
            "consent_1", "keel-api", "agent:invoke", "meta-agent", "signed-assertion")
    finally:
        server.shutdown()
        server.server_close()
    assert token == "agent-token"
    assert seen["path"] == "/oauth/delegation-token"
    assert seen["type"].startswith("application/x-www-form-urlencoded")
    assert "consent_id=consent_1" in seen["body"]
    assert "requested_audience=keel-api" in seen["body"]
    assert "requested_scopes=agent%3Ainvoke" in seen["body"]
    assert "client_assertion_type=urn%3Aietf%3Aparams%3Aoauth%3Aclient-assertion-type%3Ajwt-bearer" in seen["body"]
    with pytest.raises(DelegationDenied):
        issue_delegation_token("http://127.0.0.1:1", "consent_1", "keel-api", "agent:invoke", "meta-agent", "x")


def test_resume_with_consent_keeps_the_run_when_exchange_fails(tmp_path, monkeypatch):
    monkeypatch.setenv("KEEL_RUN_STORE", str(tmp_path / "runs.json"))
    for name in ("KEEL_AUTH_GATEWAY_URL", "KEEL_OAUTH_CLIENT_ID", "KEEL_OAUTH_PRIVATE_KEY",
                 "KEEL_OAUTH_ASSERTION_AUDIENCE", "KEEL_DELEGATION_AUDIENCE", "KEEL_DELEGATION_SCOPES"):
        monkeypatch.delenv(name, raising=False)
    from keel import Agent
    from tests.test_runtime import agent_file, call, frames
    import asyncio
    agent = Agent.from_manifest(agent_file(tmp_path))

    @agent.entry
    async def handle(req, ctx):
        return ctx.suspend("input_required", prompt="继续?")

    app = agent.asgi()
    first = asyncio.run(call(app, "POST", "/v1/invoke", json={"input": {"text": "需求"}}))
    data = frames(first)[-1]["data"]
    denied = asyncio.run(call(app, "POST", f"/v1/runs/{data['run_id']}/resume", json={
        "resume_token": data["resume_token"], "input": {"text": "继续"}, "consent_id": "consent_1"}))
    assert denied.status_code == ErrorCode.RUN_RESUME_DENIED.http
    assert denied.json()["code"] == ErrorCode.RUN_RESUME_DENIED.value
    from keel.runs import get as get_run
    assert get_run(data["run_id"])["status"] == "SUSPENDED"
    with pytest.raises(DelegationDenied):
        issue_from_environment("consent_1")


def _pad(segment: str) -> str:
    return base64.urlsafe_b64decode(segment + "=" * (-len(segment) % 4))
