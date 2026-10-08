import base64
import json
import os
import signal
import socket
import subprocess
import sys
import threading
import time
from pathlib import Path

import httpx
import jsonschema
import pytest

from keel.cli import main
from keel.cli.dev import (
    LANGFUSE_KEYS,
    LocalTraceSink,
    TraceFileWatcher,
    _append_trace,
    format_span_tree,
    injected_runtime_env,
    langfuse_mode,
    run_dev,
)
from keel.cli.new import create_project

ROOT = Path(__file__).resolve().parents[3]
SCHEMA = json.loads((ROOT / "contracts/sse-events.schema.json").read_text())
MANIFEST = json.loads((ROOT / "contracts/manifest.schema.json").read_text())
LAYOUT = (
    "agent.yaml", "app.py", "tools/.gitkeep", "prompts/.gitkeep",
    "evals/seed.jsonl", "evals/scorers.py", "tests/.gitkeep",
    "Dockerfile", ".github/workflows/keel.yml", ".gitignore",
)


def _span_payload() -> bytes:
    from opentelemetry.proto.collector.trace.v1.trace_service_pb2 import ExportTraceServiceRequest

    request = ExportTraceServiceRequest()
    scope = request.resource_spans.add().scope_spans.add()
    root = scope.spans.add()
    root.name = "agent.run"
    root.span_id = bytes.fromhex("11" * 8)
    root.trace_id = bytes.fromhex("ab" * 16)
    child = scope.spans.add()
    child.name = "greet"
    child.span_id = bytes.fromhex("22" * 8)
    child.parent_span_id = root.span_id
    child.trace_id = root.trace_id
    return request.SerializeToString()


def test_new_project_matches_schema_and_ignores_local_state(tmp_path):
    project = create_project("hello-agent", root=tmp_path)
    for relative in LAYOUT:
        assert (project / relative).is_file(), relative
    assert ".keel/" in (project / ".gitignore").read_text().splitlines()
    manifest = __import__("yaml").safe_load((project / "agent.yaml").read_text())
    jsonschema.Draft202012Validator(MANIFEST).validate(manifest)
    assert manifest["metadata"]["name"] == "hello-agent"
    assert "ctx.step('greet')" in (project / "app.py").read_text()


def test_from_spec_copies_the_template_and_keeps_the_confirmed_manifest(tmp_path, monkeypatch, capsys):
    from keel.cli.new import create_from_spec

    source = tmp_path / "draft"
    source.mkdir()
    (source / "spec.json").write_text('{"title": "需求分析师", "goal": "起草"}', encoding="utf-8")
    (source / "agent.yaml").write_text(
        "apiVersion: keel/v1\nkind: Agent\nmetadata:\n  name: spec-agent\n"
        "spec:\n  runtime:\n    language: python\n    endpoint: http://spec-agent.agents.svc:8000\n",
        encoding="utf-8")
    project = create_from_spec(source / "spec.json", root=tmp_path / "out")
    assert (project / "app.py").is_file()
    assert json.loads((project / "spec.json").read_text(encoding="utf-8"))["title"] == "需求分析师"
    manifest = __import__("yaml").safe_load((project / "agent.yaml").read_text())
    assert manifest["metadata"]["name"] == "spec-agent"
    assert manifest["spec"]["runtime"]["endpoint"] == "http://spec-agent.agents.svc:8000"
    empty = tmp_path / "empty"
    empty.mkdir()
    (empty / "spec.json").write_text("{}", encoding="utf-8")
    (empty / "agent.yaml").write_text((source / "agent.yaml").read_text(encoding="utf-8"), encoding="utf-8")
    with pytest.raises(ValueError, match="标题"):
        create_from_spec(empty / "spec.json", root=tmp_path / "out2")
    monkeypatch.chdir(tmp_path)
    with pytest.raises(SystemExit) as caught:
        main(["new", "other-name", "--from-spec", str(source / "spec.json")])
    assert caught.value.code == 2
    assert "agent.yaml" in capsys.readouterr().err


def test_new_rejects_bad_names_and_unshipped_templates(tmp_path):
    with pytest.raises(ValueError, match="metadata.name"):
        create_project("Hi", root=tmp_path)
    java_project = create_project("hello-agent", template="java-spring", root=tmp_path / "java")
    assert (java_project / "pom.xml").is_file()
    create_project("hello-agent", root=tmp_path)
    with pytest.raises(FileExistsError):
        create_project("hello-agent", root=tmp_path)


def test_main_dispatches_new_and_rejects_later_commands(tmp_path, monkeypatch, capsys):
    monkeypatch.chdir(tmp_path)
    main(["new", "hello-agent"])
    assert (tmp_path / "hello-agent" / "agent.yaml").is_file()
    assert "keel dev" in capsys.readouterr().out
    with pytest.raises(SystemExit) as caught:
        main(["retire"])
    assert caught.value.code == 2
    assert "尚未实现" in capsys.readouterr().err


def test_dev_env_injection_skips_values_the_user_already_set(tmp_path):
    values = injected_runtime_env(tmp_path, 8000, {})
    assert values["KEEL_ENV"] == "dev"
    assert values["KEEL_AUDIT_URL"] == "http://127.0.0.1:8001"
    assert values["KEEL_TRACE_BUFFER_PATH"].endswith(".keel/traces.jsonl")
    assert values["OTEL_BSP_SCHEDULE_DELAY"] == "200"
    assert "KEEL_ENV" not in injected_runtime_env(tmp_path, 8000, {"KEEL_ENV": "staging"})
    assert langfuse_mode({}, no_trace=False) == "local"
    assert langfuse_mode({"LANGFUSE_HOST": "https://langfuse.example"}, no_trace=False) == "local"
    remote = {key: "set" for key in LANGFUSE_KEYS}
    assert langfuse_mode(remote, no_trace=False) == "remote"
    assert langfuse_mode(remote, no_trace=True) == "off"


def test_span_tree_nests_child_under_root():
    assert format_span_tree(_span_payload()) == "- agent.run\n  - greet"


def test_local_sink_stores_otlp_and_prints_tree(tmp_path, capsys):
    payload = _span_payload()
    sink = LocalTraceSink(tmp_path / ".keel" / "traces.jsonl")
    sink.start()
    try:
        response = httpx.post(
            sink.url + "/api/public/otel/v1/traces", content=payload,
            headers={"content-type": "application/x-protobuf",
                     "x-langfuse-ingestion-version": "4"},
            auth=("local-dev", "local-dev"), timeout=2,
        )
        assert response.status_code == 200
        stored = json.loads(sink.path.read_text().strip())
        assert format_span_tree(base64.b64decode(stored["payload"])) == "- agent.run\n  - greet"
        assert "- agent.run\n  - greet" in capsys.readouterr().out
    finally:
        sink.close()


def test_remote_failure_buffer_prints_a_tree(tmp_path, capsys):
    path = tmp_path / "traces.jsonl"
    watcher = TraceFileWatcher(path)
    watcher.start()
    try:
        _append_trace(path, _span_payload())
        text = ""
        deadline = time.time() + 2
        while time.time() < deadline:
            time.sleep(0.1)
            text += capsys.readouterr().out
            if "agent.run" in text:
                break
        assert "Langfuse 连不上" in text
        assert "- agent.run" in text
        assert "- greet" in text
    finally:
        watcher.close()


def test_no_trace_does_not_pass_langfuse_to_the_agent(tmp_path, monkeypatch, capsys):
    create_project("hello-agent", root=tmp_path)
    monkeypatch.setenv("LANGFUSE_HOST", "https://langfuse.example")
    monkeypatch.setenv("LANGFUSE_PUBLIC_KEY", "pk")
    monkeypatch.setenv("LANGFUSE_SECRET_KEY", "sk")
    seen = {}

    class _Stopped:
        def wait(self):
            return 0

        def poll(self):
            return 0

        def send_signal(self, signum):
            return None

        def kill(self):
            return None

    def fake_popen(cmd, cwd, env):
        seen["env"] = env
        seen["cmd"] = cmd
        return _Stopped()

    def fake_lite(root, manifest, port):
        thread = threading.Thread(target=lambda: None)
        thread.start()
        return type("Server", (), {"should_exit": False, "started": True})(), thread

    monkeypatch.setattr("keel.cli.dev.subprocess.Popen", fake_popen)
    monkeypatch.setattr("keel.cli.dev._start_lite", fake_lite)
    run_dev(port=8123, no_trace=True, root=tmp_path / "hello-agent")
    assert "LANGFUSE_HOST" not in seen["env"]
    assert seen["env"]["KEEL_AUDIT_URL"] == "http://127.0.0.1:8124"
    assert "--reload" in seen["cmd"]
    output = capsys.readouterr().out
    assert "dev injected KEEL_ENV=dev" in output
    assert "Tracing to Langfuse disabled (--no-trace)." in output
    assert os.environ["LANGFUSE_HOST"] == "https://langfuse.example"


def _free_port_pair() -> int:
    while True:
        with socket.socket() as first:
            first.bind(("127.0.0.1", 0))
            port = first.getsockname()[1]
        if port >= 65534:
            continue
        with socket.socket() as second:
            try:
                second.bind(("127.0.0.1", port + 1))
            except OSError:
                continue
        return port


def _frames(response):
    result = []
    for block in response.text.strip().split("\n\n"):
        lines = block.splitlines()
        frame = {"event": lines[0].removeprefix("event: "),
                 "data": json.loads(lines[1].removeprefix("data: "))}
        jsonschema.Draft202012Validator(SCHEMA).validate(frame)
        result.append(frame)
    return result


def test_dev_serves_sse_keeps_approval_across_reload_and_prints_local_trace(tmp_path):
    project = create_project("hello-agent", root=tmp_path)
    port = _free_port_pair()
    env = os.environ.copy()
    for key in LANGFUSE_KEYS:
        env.pop(key, None)
    env["PYTHONUNBUFFERED"] = "1"
    proc = subprocess.Popen(
        [sys.executable, "-m", "keel.cli", "dev", "--port", str(port)],
        cwd=project, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        text=True, start_new_session=True,
    )
    lines: list[str] = []

    def drain():
        assert proc.stdout is not None
        for line in proc.stdout:
            lines.append(line)

    threading.Thread(target=drain, daemon=True).start()
    try:
        base = f"http://127.0.0.1:{port}"
        lite = f"http://127.0.0.1:{port + 1}"
        deadline = time.time() + 20
        health = None
        while time.time() < deadline:
            if proc.poll() is not None:
                break
            try:
                health = httpx.get(base + "/v1/health", timeout=0.5)
            except httpx.HTTPError:
                time.sleep(0.1)
                continue
            if health.status_code == 200:
                break
        assert health is not None and health.status_code == 200, "".join(lines)
        assert health.json()["agent"] == "hello-agent"

        invoked = httpx.post(base + "/v1/invoke", json={"input": {"text": "hi"}}, timeout=5)
        frames = _frames(invoked)
        assert [frame["event"] for frame in frames] == ["step", "final"]
        assert frames[-1]["data"]["answer"] == "Hello from hello-agent"

        trace_path = project / ".keel" / "traces.jsonl"
        trace_deadline = time.time() + 5
        while time.time() < trace_deadline and not trace_path.exists():
            time.sleep(0.1)
        assert trace_path.is_file(), "".join(lines)
        trees = [
            format_span_tree(base64.b64decode(json.loads(line)["payload"]))
            for line in trace_path.read_text().splitlines() if line.strip()
        ]
        assert any("agent.run" in tree and "greet" in tree for tree in trees)
        output = "".join(lines)
        assert "dev injected KEEL_ENV=dev" in output
        assert "dev injected KEEL_AUDIT_URL=" in output
        assert "Langfuse 未配置，追踪已降级到本地。" in output
        assert "trace" in output and "agent.run" in output

        created = httpx.post(lite + "/api/v1/approvals", json={
            "subjectType": "tool.call", "subjectRef": "work_order_create",
            "agent": "hello-agent", "summary": "创建工单?", "risk": "HIGH",
        }, timeout=5)
        assert created.status_code == 200, created.text
        approval_id = created.json()["data"]["id"]

        app_path = project / "app.py"
        app_path.write_text(app_path.read_text().replace(
            "Hello from hello-agent", "Hello reloaded"))
        reloaded = None
        reload_deadline = time.time() + 20
        while time.time() < reload_deadline:
            try:
                response = httpx.post(base + "/v1/invoke", json={"input": {"text": "hi"}}, timeout=5)
            except httpx.HTTPError:
                time.sleep(0.2)
                continue
            if response.status_code == 200 and "Hello reloaded" in response.text:
                reloaded = response
                break
            time.sleep(0.2)
        assert reloaded is not None, "".join(lines)
        assert _frames(reloaded)[-1]["data"]["answer"] == "Hello reloaded"

        pending = httpx.get(lite + "/api/v1/approvals", params={"status": "PENDING"}, timeout=5)
        assert pending.status_code == 200
        assert any(item["id"] == approval_id for item in pending.json()["data"]["items"])
        decision = httpx.post(
            f"{lite}/api/v1/approvals/{approval_id}/decision",
            json={"decision": "APPROVE"}, timeout=5,
        )
        assert decision.status_code == 200, decision.text
        assert decision.json()["data"]["status"] == "APPROVED"
    finally:
        if proc.poll() is None:
            os.killpg(proc.pid, signal.SIGTERM)
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                os.killpg(proc.pid, signal.SIGKILL)
                proc.wait(timeout=5)
