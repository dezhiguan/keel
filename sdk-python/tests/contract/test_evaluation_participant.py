"""Synthetic contract example only, never a real authenticated participant."""
import json
from pathlib import Path
import jsonschema
import pytest
from keel._generated.evaluation_participant import EvaluationParticipant

ROOT=Path(__file__).resolve().parents[3]/'contracts'


def test_participant_schema_and_generated_model_preserve_identity_scope_and_receipt():
    example=json.loads((ROOT/'examples/evaluation-participant.json').read_text())
    schema=json.loads((ROOT/'evaluation-participant.schema.json').read_text())
    jsonschema.Draft202012Validator(schema,format_checker=jsonschema.FormatChecker()).validate(example)
    model=EvaluationParticipant.model_validate(example)
    assert model.ownerFingerprint==example['ownerFingerprint']
    assert model.storage.messageCount==2 and model.participantIndex==1


@pytest.mark.parametrize('field,value',[('participantIndex',2),('ownerFingerprint','claimed-user'),('env','prod'),('authenticationSource','model')])
def test_invalid_participant_wire_scope_is_rejected(field,value):
    example=json.loads((ROOT/'examples/evaluation-participant.json').read_text());example[field]=value
    schema=json.loads((ROOT/'evaluation-participant.schema.json').read_text())
    with pytest.raises(jsonschema.ValidationError):jsonschema.validate(example,schema)
