"""Public manifest model generated from the keel/v1 contract."""

import json
from importlib.resources import files
from pathlib import Path

import jsonschema
import yaml

from keel._generated.manifest import AgentManifest


def _contract_file(name: str) -> str:
    packaged = files("keel").joinpath("_schemas", name)
    if packaged.is_file():
        return packaged.read_text(encoding="utf-8")
    # Editable checkout: the build includes these same contract files in the wheel.
    return (Path(__file__).resolve().parents[2] / "contracts" / name).read_text(encoding="utf-8")


def load_manifest(path: str | Path) -> AgentManifest:
    data = yaml.safe_load(Path(path).read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator(json.loads(_contract_file("manifest.schema.json"))).validate(data)
    return AgentManifest.model_validate(data)


__all__ = ["AgentManifest", "load_manifest"]
