import copy
import json
from pathlib import Path

import pytest
from keel.routing import validate_intent_packet, validate_route_plan, validate_routing_context

ROOT = Path(__file__).resolve().parents[3] / 'contracts/tests/fixtures'

def fixture(name):return json.loads((ROOT / name).read_text())

@pytest.mark.parametrize('change', ['cycle','duplicate','output_without_dependency','condition_without_dependency'])
def test_plan_semantics_reject_invalid_dependencies(change):
    plan=fixture('route-plan/valid/local-read.json')
    step=plan['steps'][0]
    if change=='cycle':step['depends_on']=['read']
    if change=='duplicate':plan['steps'].append(copy.deepcopy(step))
    if change=='output_without_dependency':step['input_refs']=['step:missing.output']
    if change=='condition_without_dependency':step['precondition']={'kind':'step_succeeded','step_id':'missing'}
    with pytest.raises(ValueError):validate_route_plan(plan)

def test_valid_models_keep_references_and_do_not_add_authority():
    packet=validate_intent_packet(fixture('intent-packet/valid/employee.json'))
    context=validate_routing_context(fixture('routing-context/valid/local.json'))
    plan=validate_route_plan(fixture('route-plan/valid/local-read.json'))
    assert packet['slot_sources']['env']=='current_message'
    assert context['authorization_ref']=='authorization:reference'
    assert plan['steps'][0]['max_attempts']==1

def test_provenance_cannot_name_a_missing_slot():
    packet=fixture('intent-packet/valid/employee.json')
    packet['slot_sources']['task_id']='runtime'
    with pytest.raises(ValueError):validate_intent_packet(packet)
