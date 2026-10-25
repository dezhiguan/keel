"""Code-owned routing evidence. Validation never grants execution authority."""

import copy
import json
from functools import lru_cache

import jsonschema

from keel._generated.routing import IntentRouting
from keel._generated.intent_packet import IntentPacket
from keel._generated.route_plan import RoutePlan
from keel._generated.routing_context import RoutingContext
from keel.manifest import _contract_file


@lru_cache(maxsize=1)
def _schema():
    return json.loads(_contract_file("intent-routing.schema.json"))


def routing_schema() -> dict:
    return copy.deepcopy(_schema())


@lru_cache(maxsize=4)
def _named_schema(name):
    return json.loads(_contract_file(name + ".schema.json"))


def intent_packet_schema():
    return copy.deepcopy(_named_schema("intent-packet"))


def _validate(name, value, model):
    jsonschema.Draft202012Validator(_named_schema(name), format_checker=jsonschema.FormatChecker()).validate(value)
    return model.model_validate(value).model_dump(mode="json")


def validate_intent_packet(value):
    result = _validate("intent-packet", value, IntentPacket)
    if result['operation'] == 'retry' and result['clarity'] == 'clear' and result['slots'].get('duplicate_cost_confirmed') is not True:
        raise ValueError('Retry requires explicit duplicate-cost confirmation')
    if set(result['slot_sources']) - set(result['slots']):
        raise ValueError('Slot provenance has no corresponding value')
    return result


def validate_routing_context(value):
    return _validate("routing-context", value, RoutingContext)


def validate_route_plan(value):
    result = _validate("route-plan", value, RoutePlan)
    _validate_graph(result)
    return result


def validate_routing(value: dict) -> dict:
    """Validate wire types plus step identities/dependencies before publication."""
    jsonschema.Draft202012Validator(_schema()).validate(value)
    _validate_graph(value['plan'])
    if value['intent'].get('schema_version'):
        validate_intent_packet(value['intent'])
    if value['plan'].get('schema_version'):
        validate_route_plan(value['plan'])
        if value['plan']['policy_version'] != value['policy_version']:
            raise ValueError('Plan policy version mismatch')
    if value.get('context'):
        validate_routing_context(value['context'])
        if value['context']['request_id'] != value['plan'].get('request_id'):
            raise ValueError('Routing context request mismatch')
        for key in ('policy_version', 'capability_revision', 'deadline_at'):
            if value['context'][key] != value['plan'].get(key):
                raise ValueError('Routing context plan binding mismatch')
    step_ids = {step['step_id'] for step in value['plan']['steps']}
    if any(call.get('step_id') and call['step_id'] not in step_ids for call in value['actual_calls']):
        raise ValueError('Actual call references an unknown step')
    return IntentRouting.model_validate(value).model_dump(mode="json")


def _validate_graph(plan):
    steps = plan["steps"]
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
    for step in steps:
        condition = step.get('precondition', {})
        if condition.get('kind') == 'step_succeeded' and condition.get('step_id') not in step['depends_on']:
            raise ValueError('Step condition must reference a dependency')
        for ref in step.get('input_refs', []):
            if ref.startswith('step:'):
                parent = ref[5:].split('.', 1)[0]
                if parent not in step['depends_on']:
                    raise ValueError('Step output reference must name a dependency')
    for route in [plan, *steps]:
        if route["mode"] in ("deny", "clarify", "approval") and route.get("executor"):
            raise ValueError("Non-executing route cannot name an executor")


__all__ = ["IntentRouting", "IntentPacket", "RoutePlan", "RoutingContext", "routing_schema", "intent_packet_schema", "validate_routing", "validate_intent_packet", "validate_route_plan", "validate_routing_context"]
