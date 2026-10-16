import json
import threading
from http.server import ThreadingHTTPServer
from pathlib import Path

import fake_langfuse
import fake_llm
import runner


def test_sandbox_image_installs_async_pytest_support():
    dockerfile = (Path(__file__).resolve().parent / "Dockerfile").read_text(encoding="utf-8")
    assert "pytest-asyncio==1.4.0" in dockerfile


def test_runner_executes_exact_snapshot_with_in_pod_fakes(tmp_path, monkeypatch):
    root = Path(__file__).resolve().parent
    source = "\n".join((root / name).read_text() for name in ("fake_llm.py", "fake_langfuse.py", "runner.py"))
    for banned in ("dashscope", "openai", "anthropic", "langfuse.com", "api.github.com"):
        assert banned not in source

    llm = ThreadingHTTPServer(("127.0.0.1", 0), fake_llm.Handler)
    langfuse = ThreadingHTTPServer(("127.0.0.1", 0), fake_langfuse.Handler)
    threads = [threading.Thread(target=server.serve_forever, daemon=True) for server in (llm, langfuse)]
    for thread in threads:
        thread.start()
    snapshot = tmp_path / "bundle.json"
    sha = "a" * 40
    snapshot.write_text(json.dumps({"sha": sha, "files": [
        {"path": "app.py", "content": "VALUE = 42\n"},
        {"path": "agent.yaml", "content": "kind: Agent\n"},
        {"path": "tests/test_role.py", "content": "from app import VALUE\n\ndef test_value():\n    assert VALUE == 42\n"},
    ]}), encoding="utf-8")
    monkeypatch.setenv("KEEL_SANDBOX_REF", sha)
    try:
        report, ok = runner.check(
            f"http://127.0.0.1:{llm.server_address[1]}",
            f"http://127.0.0.1:{langfuse.server_address[1]}",
            snapshot_path=snapshot,
            work_dir=tmp_path / "work",
            attempts=3,
            pause=0.05,
        )
    finally:
        llm.shutdown()
        langfuse.shutdown()
    assert ok
    assert f"sha={sha} snapshot=ok tests=PASS" in report
    assert "1 passed" in report


def test_runner_rejects_missing_snapshot_without_false_success(tmp_path, monkeypatch):
    monkeypatch.setattr(runner, "_post", lambda *_args: True)
    report, ok = runner.check("http://fake-llm", "http://fake-trace",
                              snapshot_path=tmp_path / "missing.json", work_dir=tmp_path / "work")
    assert not ok
    assert "tests=NOT_RUN" in report


def test_runner_rejects_path_traversal_and_commit_mismatch(tmp_path, monkeypatch):
    monkeypatch.setattr(runner, "_post", lambda *_args: True)
    snapshot = tmp_path / "bundle.json"
    snapshot.write_text(json.dumps({"sha": "a" * 40, "files": [
        {"path": "../escape", "content": "leak"}]}), encoding="utf-8")
    monkeypatch.setenv("KEEL_SANDBOX_REF", "a" * 40)
    report, ok = runner.check("http://fake-llm", "http://fake-trace", snapshot_path=snapshot,
                              work_dir=tmp_path / "work")
    assert not ok and "snapshot=invalid" in report
    assert not (tmp_path / "escape").exists()
    monkeypatch.setenv("KEEL_SANDBOX_REF", "b" * 40)
    report, ok = runner.check("http://fake-llm", "http://fake-trace", snapshot_path=snapshot,
                              work_dir=tmp_path / "work")
    assert not ok and "SHA differs" in report
