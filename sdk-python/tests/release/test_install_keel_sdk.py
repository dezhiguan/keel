import json
import threading
import zipfile
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

import pytest

from keel.cli.install_keel_sdk import download_wheel, main, pip_install_args, version_from_tag, wheel_name

ROOT = Path(__file__).resolve().parents[3]


def test_tag_must_stay_on_the_v1_line():
    assert version_from_tag("sdk-v1.0.0") == "1.0.0"
    assert version_from_tag("sdk-v1.2.3") == "1.2.3"
    for tag in ("v1.0.0", "sdk-v0.1.0", "sdk-v2.0.0", "sdk-v1.0", "sdk-v1.0.0-rc1"):
        with pytest.raises(ValueError):
            version_from_tag(tag)
    with pytest.raises(ValueError):
        wheel_name("0.1.0")


def test_pip_installs_the_wheel_file_not_the_package_name(tmp_path):
    wheel = tmp_path / wheel_name("1.0.0")
    wheel.write_bytes(b"wheel")
    args = pip_install_args(wheel)
    assert args[-1] == str(wheel)
    assert "keel-sdk" not in args
    with pytest.raises(ValueError):
        pip_install_args(tmp_path / "keel-sdk")


def test_download_uses_the_release_asset_and_not_pypi(tmp_path):
    payload = b"wheel-bytes"
    seen = []

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            seen.append((self.path, self.headers["Authorization"], self.headers["Accept"]))
            if self.path == "/repos/dezhiguan/keel/releases/tags/sdk-v1.0.0":
                body = json.dumps({"assets": [{
                    "name": "keel_sdk-1.0.0-py3-none-any.whl",
                    "url": f"http://127.0.0.1:{server.server_address[1]}/assets/1",
                }]}).encode()
                self._send(200, body)
                return
            if self.path == "/assets/1":
                self._send(200, payload)
                return
            self._send(404, b"missing")

        def _send(self, status, body):
            self.send_response(status)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, format, *args):
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        dest = tmp_path / wheel_name("1.0.0")
        download_wheel(
            f"http://127.0.0.1:{server.server_address[1]}",
            "dezhiguan/keel", "1.0.0", "test-token", dest,
        )
    finally:
        server.shutdown()
    assert dest.read_bytes() == payload
    assert seen[0][0] == "/repos/dezhiguan/keel/releases/tags/sdk-v1.0.0"
    assert seen[0][1] == "Bearer test-token"
    assert seen[1][2] == "application/octet-stream"
    assert all("pypi" not in path for path, _auth, _accept in seen)


def test_install_invokes_pip_on_the_downloaded_file(monkeypatch):
    calls = []

    def fake_download(_api, _repo, version, token, dest):
        assert token == "test-token"
        assert version == "1.0.0"
        dest.write_bytes(b"wheel")
        return dest

    monkeypatch.setenv("KEEL_GITHUB_TOKEN", "test-token")
    monkeypatch.setenv("KEEL_SDK_VERSION", "1.0.0")
    monkeypatch.setattr("keel.cli.install_keel_sdk.download_wheel", fake_download)
    monkeypatch.setattr("keel.cli.install_keel_sdk.subprocess.check_call", lambda args: calls.append(args))
    main([])
    assert calls[0][-1].endswith("keel_sdk-1.0.0-py3-none-any.whl")
    assert "keel-sdk" not in calls[0]


def test_missing_token_does_not_download(monkeypatch):
    monkeypatch.delenv("KEEL_GITHUB_TOKEN", raising=False)
    with pytest.raises(SystemExit, match="KEEL_GITHUB_TOKEN"):
        main([])


def test_meta_agent_image_does_not_copy_platform_source():
    root = ROOT
    dockerfile = (root / "agents/meta-agent/Dockerfile").read_text(encoding="utf-8")
    workflow = (root / ".github/workflows/keel-cd.yml").read_text(encoding="utf-8")
    assert "context: agents/meta-agent" in workflow
    assert "file: agents/meta-agent/Dockerfile" in workflow
    assert "COPY sdk-python" not in dockerfile
    assert "COPY contracts" not in dockerfile
    assert "COPY templates" not in dockerfile
    assert "langfuse==4.17.0" in dockerfile
    example = (root / "examples/meta-agent/agent.yaml").read_text(encoding="utf-8")
    deploy = (root / "deploy/k3s/services/meta-agent.yaml").read_text(encoding="utf-8")
    assert "example-meta" in example
    assert "example-meta" not in deploy


def test_release_workflow_publishes_only_the_starter_line():
    workflow = (ROOT / ".github/workflows/release-sdk.yml").read_text(encoding="utf-8")
    assert "sdk-v1.*" in workflow
    assert "install_keel_sdk.py --check-tag" in workflow
    assert "mvn -B deploy -N -DskipTests" in workflow
    assert "mvn -B deploy -pl keel-common,keel-spring-boot-starter -DskipTests" in workflow
    for module in ("keel-server", "keel-gateway", "keel-audit", "keel-llm"):
        assert module not in workflow
    assert "https://maven.pkg.github.com/dezhiguan/keel" in (ROOT / "pom.xml").read_text(encoding="utf-8")


def test_release_wheel_carries_the_contract_schemas():
    import os
    import subprocess
    import sys

    env = os.environ.copy()
    env["KEEL_SDK_VERSION"] = "1.0.0"
    env["KEEL_PYTHON_BIN"] = sys.executable
    completed = subprocess.run(["bash", str(ROOT / "scripts/build-template-wheel.sh")],
                               cwd=ROOT, env=env, text=True, capture_output=True, check=True)
    wheel = Path(completed.stdout.strip().splitlines()[-1])
    assert wheel.name == "keel_sdk-1.0.0-py3-none-any.whl"
    with zipfile.ZipFile(wheel) as archive:
        assert archive.testzip() is None
        names = archive.namelist()
        assert "keel/_schemas/manifest.schema.json" in names
        assert "keel/_templates/loop-agent/agent.yaml" in names
