"""Canonical audit bytes shared in meaning with contracts/tests/README.md."""

import hashlib
import json

from keel._generated.audit import AuditEvent


def _normalize(value, path=()):
    if isinstance(value, dict):
        return {key: _normalize(item, (*path, key)) for key, item in value.items()
                if item is not None}
    if isinstance(value, list):
        return [_normalize(item, (*path, index)) for index, item in enumerate(value)]
    if isinstance(value, float):
        raise ValueError("floating value in audit event at /" + "/".join(map(str, path)))
    if path == ("ts",) and isinstance(value, str) and value.endswith("Z") and "." in value:
        whole, fraction = value[:-1].split(".", 1)
        fraction = fraction.rstrip("0")
        return f"{whole}.{fraction}Z" if fraction else f"{whole}Z"
    return value


def canonical_json(event: AuditEvent) -> str:
    data = event.model_dump(mode="json")
    data.pop("hash")
    return json.dumps(_normalize(data), ensure_ascii=False, sort_keys=True,
                      separators=(",", ":"), allow_nan=False)


def chain_hash(event: AuditEvent) -> str:
    return hashlib.sha256(((event.prev_hash or "") + canonical_json(event)).encode("utf-8")).hexdigest()
