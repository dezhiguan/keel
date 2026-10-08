"""Release an image after a passing gate run."""

import sys
from pathlib import Path

from keel.cli.server import ServerRejected, post_json
from keel.manifest import load_manifest


def release(*, env: str, image: str, gate_run_id: str | None, manifest_file: str = "agent.yaml", root: str | Path = ".") -> dict:
    if not image.strip():
        raise ValueError("image 不能为空")
    run_id = gate_run_id or _saved_run_id(root)
    name = load_manifest(Path(root) / manifest_file).metadata.name
    try:
        record = post_json(f"/api/v1/agents/{name}/releases", {"env": env, "gateRunId": run_id, "image": image})
    except ServerRejected as error:
        print(f"{error.code} {error}", file=sys.stderr)
        raise SystemExit(1)
    print(f"released {name} env={env} gateRunId={run_id}")
    return record


def _saved_run_id(root: str | Path) -> str:
    path = Path(root) / ".keel" / "gate-run-id"
    if not path.is_file():
        raise ValueError("没有 gateRunId。先跑 keel gate，或传入 --gate-run-id")
    run_id = path.read_text(encoding="utf-8").strip()
    if not run_id:
        raise ValueError("没有 gateRunId。先跑 keel gate，或传入 --gate-run-id")
    return run_id
