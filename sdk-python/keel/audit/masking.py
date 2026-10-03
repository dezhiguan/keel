"""Keep only manifest audit.captureFields before sending an event."""

from collections.abc import Collection, Mapping


def capture_fields(payload: Mapping, allowed: Collection[str]) -> dict:
    permitted = set(allowed)
    return {key: value for key, value in payload.items() if key in permitted}
