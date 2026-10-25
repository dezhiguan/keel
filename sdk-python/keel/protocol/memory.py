"""Optional keel/v1 durable memory lease returned by the authenticated server."""
from typing import Literal
from pydantic import BaseModel, Field, ConfigDict, StrictInt

class MaintenanceJob(BaseModel):
    model_config=ConfigDict(extra='ignore',populate_by_name=True)
    id: StrictInt = Field(gt=0)
    kind: Literal['summary','facts']
    env: Literal['staging','prod','dev','test']
    owner_user: str = Field(alias='ownerUser',min_length=1,max_length=128)
    session_id: str = Field(alias='sessionId',pattern=r'^[a-f0-9]{64}$')
    target_id: str = Field(alias='targetId',pattern=r'^[1-9][0-9]{0,17}$')
    expected_version: StrictInt = Field(alias='expectedVersion',ge=0)
    lease_token: str = Field(alias='leaseToken',min_length=16,max_length=64)
    trigger_trace_id: str = Field(alias='triggerTraceId',max_length=64)
    attempt: StrictInt = Field(ge=1,le=3)
    max_calls: StrictInt = Field(alias='maxCalls',ge=1,le=4)
    max_input_tokens: StrictInt = Field(alias='maxInputTokens',ge=128,le=10000)
    max_output_tokens: StrictInt = Field(alias='maxOutputTokens',ge=1,le=1600)
    max_cost_cny: float = Field(alias='maxCostCny',gt=0,le=.5,allow_inf_nan=False)
    state: dict
    messages: list[dict] = Field(max_length=130)
    skipped_ids: list[str] = Field(alias='skippedIds',max_length=128)
    next_cursor: str = Field(alias='nextCursor',pattern=r'^[0-9]{1,18}$')
    more: bool
