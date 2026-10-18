"""Exercise trusted checks against good and deliberately broken role implementations."""
import importlib.util
import json
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest
import yaml


@pytest.fixture
def checks(tmp_path, monkeypatch):
    (tmp_path / 'tests').mkdir()
    (tmp_path / 'evals').mkdir()
    (tmp_path / 'agent.yaml').write_text(yaml.safe_dump({'spec': {'tools': [
        {'name': 'devflow.job.read'}, {'name': 'devflow.catalog.search'}]}}))
    (tmp_path / 'evals/seed.jsonl').write_text(json.dumps({
        'input': {'text': '请查询任务DF-1234的状态'}, 'expected': {'decision': 'PASS'}}))
    source = Path(__file__).resolve().parents[3] / 'services/sandbox-sidecar/platform_contract_test.py'
    target = tmp_path / 'tests/test_platform_contract.py'
    target.write_text(source.read_text())
    behavior = {'invalid_json': False, 'promote_data': False, 'ignore_empty': False, 'ignore_shape': False}

    async def execute(data, ctx):
        job = await ctx.tools.call('devflow.job.read', jobId='DF-1234')
        catalog = await ctx.tools.call('devflow.catalog.search', query='')
        if not behavior['ignore_shape'] and (any(not isinstance(item, dict) or not item.get('status') for item in job)
                or any(not isinstance(item, dict) for item in catalog)):
            return {'decision': 'BLOCKED'}
        if not job and not behavior['ignore_empty']:
            return {'decision': 'BLOCKED'}
        prompt = await ctx.prompt('role')
        material = json.dumps({'input': data, 'evidence': [job, catalog]})
        reply = await ctx.llm.chat([
            {'role': 'system', 'content': material if behavior['promote_data'] else prompt.compile()},
            {'role': 'user', 'content': material}], prompt=prompt)
        result = json.loads(reply)
        if not behavior['invalid_json'] and (not isinstance(result, dict)
                or result.get('decision') not in ('PASS', 'BLOCKED')):
            return {'decision': 'BLOCKED'}
        return {'decision': result.get('decision', 'PASS')}

    monkeypatch.setitem(sys.modules, 'role', SimpleNamespace(execute=execute))
    spec = importlib.util.spec_from_file_location('temporary_platform_contract', target)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, behavior


def test_catalog_only_agent_has_real_enums_and_trust_boundary_checks(checks):
    module, behavior = checks
    module.test_approved_normal_case_passes_with_complete_evidence()
    module.test_user_and_tool_material_cannot_become_system_instructions()
    behavior['promote_data'] = True
    with pytest.raises(AssertionError, match='promoted'):
        module.test_user_and_tool_material_cannot_become_system_instructions()


def test_contract_detects_missing_json_object_validation(checks):
    module, behavior = checks
    for response in ('null', '[]', '"PASS"', 'true', '{"decision":null}'):
        module.test_non_object_or_invalid_model_decision_is_blocked(response)
    behavior['invalid_json'] = True
    with pytest.raises(AttributeError):
        module.test_non_object_or_invalid_model_decision_is_blocked('null')


def test_contract_detects_empty_job_evidence(checks):
    module, behavior = checks
    module.test_empty_task_evidence_is_blocked()
    behavior['ignore_empty'] = True
    with pytest.raises(AssertionError):
        module.test_empty_task_evidence_is_blocked()


def test_contract_detects_malformed_records_even_if_model_says_pass(checks):
    module, behavior = checks
    for tool, value in [('devflow.job.read', [None]), ('devflow.job.read', ['invalid']),
                        ('devflow.job.read', [{}]), ('devflow.catalog.search', [None]),
                        ('devflow.catalog.search', ['invalid'])]:
        module.test_malformed_record_evidence_is_blocked_before_model_pass(tool, value)
    behavior['ignore_shape'] = True
    with pytest.raises(AssertionError):
        module.test_malformed_record_evidence_is_blocked_before_model_pass('devflow.job.read', [None])
