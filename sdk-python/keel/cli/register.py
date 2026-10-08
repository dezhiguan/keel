"""Register the current agent.yaml with keel-server."""

from pathlib import Path

import yaml

from keel.cli.server import ServerRejected, post_json
from keel.manifest import load_manifest


def register(*, env: str, manifest_file: str = "agent.yaml") -> dict:
    path = Path(manifest_file)
    manifest = load_manifest(path)
    body = yaml.safe_load(path.read_text(encoding="utf-8"))
    body["env"] = env
    try:
        report = post_json("/api/v1/agents", body)
    except ServerRejected as error:
        raise ValueError(f"{error.code} {error}") from error
    print(f"registered {manifest.metadata.name} env={env}")
    return report
