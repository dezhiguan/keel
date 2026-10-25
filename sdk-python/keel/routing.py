"""Code-owned routing evidence. Validation never grants execution authority."""

import copy
import json
from functools import lru_cache

import jsonschema

from keel._generated.routing import IntentRouting
from keel.manifest import _contract_file


@lru_cache(maxsize=1)
def _schema():
    return json.loads(_contract_file("intent-routing.schema.json"))


def routing_schema() -> dict:
    return copy.deepcopy(_schema())


def validate_routing(value: dict) -> dict:
    """Validate wire types plus step identities/dependencies before publication."""
    jsonschema.Draft202012Validator(_schema()).validate(value)
    steps = value["plan"]["steps"]
    by_id = {step["step_id"]: step for step in steps}
    if len(by_id) != len(steps):
        raise ValueError("Duplicate route step")
    visiting, visited = set(), set()

    def visit(key):
        if key not in by_id or key in visiting:
            raise ValueError("Missing or cyclic route dependency")
        if key in visited:
            return
        visiting.add(key)
        for parent in by_id[key]["depends_on"]:
            visit(parent)
        visiting.remove(key)
        visited.add(key)

    for key in by_id:
        visit(key)
    for route in [value["plan"], *steps]:
        if route["mode"] in ("deny", "clarify", "approval") and route.get("executor"):
            raise ValueError("Non-executing route cannot name an executor")
    return IntentRouting.model_validate(value).model_dump(mode="json")


__all__ = ["IntentRouting", "routing_schema", "validate_routing"]
