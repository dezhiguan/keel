"""The same cases.yaml and fixtures are consumed by the Java contract test."""

import hashlib
import json
from pathlib import Path

import jsonschema
import pytest
import yaml

from keel._generated.audit import AuditEvent
from keel._generated.events import (ErrorEvent, FinalEvent, StepEvent,
                                    SuspendEvent, TokenEvent, ToolEvent)
from keel._generated.manifest import AgentManifest

ROOT = Path(__file__).resolve().parents[3] / "contracts"
SUITE = ROOT / "tests"
CASES = yaml.safe_load((SUITE / "cases.yaml").read_text(encoding="utf-8"))
SCHEMAS = {
    name: json.loads((ROOT / f"{name}.schema.json").read_text(encoding="utf-8"))
    for name in ("manifest", "sse-events", "audit-event")
}
EVENTS = {
    "step": StepEvent, "tool": ToolEvent, "token": TokenEvent,
    "final": FinalEvent, "error": ErrorEvent, "suspend": SuspendEvent,
}


def pointer(parts):
    return "".join("/" + str(part).replace("~", "~0").replace("/", "~1") for part in parts)


def schema_errors(schema, document):
    result = set()
    for error in jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker()).iter_errors(document):
        path = list(error.path)
        if error.validator == "required":
            missing = next(key for key in error.validator_value if key not in error.instance)
            path.append(missing)
        result.add(pointer(path))
    return result


def canonical_object(value, path=()):
    if isinstance(value, dict):
        return {key: canonical_object(item, (*path, key)) for key, item in value.items()
                if item is not None}
    if isinstance(value, list):
        return [canonical_object(item, (*path, index)) for index, item in enumerate(value)]
    if isinstance(value, float):
        raise ValueError(pointer(path))
    if path == ("ts",) and isinstance(value, str) and value.endswith("Z") and "." in value:
        whole, fraction = value[:-1].split(".", 1)
        fraction = fraction.rstrip("0")
        return f"{whole}.{fraction}Z" if fraction else f"{whole}Z"
    return value


def digest(event):
    data = event.model_dump(mode="json")
    data.pop("hash")
    data = canonical_object(data)
    encoded = json.dumps(data, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(((event.prev_hash or "") + encoded).encode("utf-8")).hexdigest()


@pytest.mark.parametrize("case", CASES, ids=[case["file"] for case in CASES])
def test_contract(case):
    path = SUITE / "fixtures" / case["file"]
    document = yaml.safe_load(path.read_text(encoding="utf-8")) if path.suffix == ".yaml" else json.loads(path.read_text(encoding="utf-8"))
    family = case["file"].split("/")[0]
    if family == "canonical":
        family = "audit-event"
    schema = SCHEMAS[family]
    root_errors = schema_errors(schema, document) if family == "sse-events" else set()
    if family == "sse-events" and document.get("event") in EVENTS:
        schema = {"$defs": schema["$defs"], "$ref": f"#/$defs/frames/{document['event']}"}
    selected_errors = schema_errors(schema, document)
    errors = selected_errors or root_errors
    if family == "manifest" and "registered_agents" in case:
        allowed = set(case["registered_agents"])
        errors.update(pointer(("spec", "delegates", i)) for i, name in
                      enumerate(document.get("spec", {}).get("delegates", [])) if name not in allowed)
    if family == "audit-event" and "capture_fields" in case:
        allowed = set(case["capture_fields"])
        errors.update(pointer(("payload", name)) for name in
                      document.get("payload", {}) if name not in allowed)
    digest_value = None
    if family == "audit-event" and not errors:
        try:
            event = AuditEvent.model_validate(document)
            digest_value = digest(event) if case["file"].startswith("canonical/") else None
        except ValueError as exc:
            if str(exc).startswith("/"):
                errors.add(str(exc))
            else:
                raise
    elif not errors:
        if family == "manifest":
            AgentManifest.model_validate(document)
        elif family == "sse-events":
            EVENTS[document["event"]].model_validate(document["data"])
    if case["expect"] == "reject":
        assert errors == {case["reason"]}, f"{path}: expected {case['reason']}, got {errors}"
    else:
        assert not errors, f"{path}: {errors}"
        if "canonical_sha256" in case:
            assert digest_value == case["canonical_sha256"]


def test_cases_cover_every_fixture():
    files = {str(path.relative_to(SUITE / "fixtures")) for path in (SUITE / "fixtures").rglob("*") if path.is_file()}
    assert {case["file"] for case in CASES} == files


def test_null_fields_would_change_the_hash():
    case = next(case for case in CASES if case["file"] == "canonical/null-omission.json")
    data = json.loads((SUITE / "fixtures" / case["file"]).read_text(encoding="utf-8"))
    event = AuditEvent.model_validate(data)
    wrong = event.model_dump(mode="json", exclude_none=False)
    wrong.pop("hash")
    encoded = json.dumps(wrong, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    assert hashlib.sha256(((event.prev_hash or "") + encoded).encode()).hexdigest() != case["canonical_sha256"]
