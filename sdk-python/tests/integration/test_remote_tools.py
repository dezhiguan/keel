import asyncio
import base64
import json
import httpx
import pytest
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.hazmat.primitives import serialization
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from keel.context import Context
from keel.manifest import AgentManifest
from keel.protocol.errors import ErrorCode, KeelError


@pytest.mark.parametrize("denied", [False, True])
def test_shared_call_has_fresh_identity_real_tool_span_and_propagates_denial(monkeypatch, denied):
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                            serialization.NoEncryption()).decode()
    for name, value in {"KEEL_SERVER_URL": "https://server.test", "KEEL_SERVICE_AUDIENCE": "keel-server",
                        "KEEL_CLIENT_PRIVATE_KEY": pem}.items():
        monkeypatch.setenv(name, value)
    seen = []

    def handle(request):
        seen.append(request)
        return httpx.Response(403, json={"code": "TOOL_NOT_GRANTED"}) if denied else httpx.Response(
            200, json={"code": "OK", "data": {"diff": "diff --git a/example b/example"}})

    original = httpx.AsyncClient
    monkeypatch.setattr(httpx, "AsyncClient", lambda **kwargs: original(transport=httpx.MockTransport(handle), **kwargs))
    provider = TracerProvider()
    memory = InMemorySpanExporter()
    provider.add_span_processor(SimpleSpanProcessor(memory))
    manifest = AgentManifest.model_validate({"apiVersion": "keel/v1", "metadata": {"name": "qa-agent"},
        "spec": {"runtime": {"endpoint": "http://agent"}, "tools": [{"name": "git.pr.diff", "risk": "low", "access": "read"}]}})
    context = Context("qa-agent", "run-1", "a" * 32, asyncio.Queue(), provider.get_tracer("test"), manifest=manifest, env="dev")

    async def call():
        try:
            if denied:
                with pytest.raises(KeelError) as error:
                    await context.tools.call("git.pr.diff", repo="example", number=2)
                assert error.value.code == ErrorCode.TOOL_NOT_GRANTED
            else:
                assert "diff --git" in (await context.tools.call("git.pr.diff", repo="example", number=2))["diff"]
        finally:
            await context.aclose()
    asyncio.run(call())
    claims = seen[0].headers["X-Client-Assertion"].split('.')[1]
    claims = json.loads(base64.urlsafe_b64decode(claims + '=' * (-len(claims) % 4)))
    assert claims["iss"] == claims["sub"] == "qa-agent"
    assert claims["aud"] == "keel-server"
    assert seen[0].headers["X-Keel-Trace-Id"] == "a" * 32
    span = memory.get_finished_spans()[0]
    assert span.name == "git.pr.diff"
    assert span.attributes["langfuse.observation.type"] == "tool"
    assert span.attributes["keel.status"] == ("failed" if denied else "ok")
