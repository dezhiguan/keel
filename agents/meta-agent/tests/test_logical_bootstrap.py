import pytest
from ops.bootstrap_runtime import logical_key_request


def policy(**changes):
    return {'alias':'meta-agent-dev','blocked':False,'models':['qwen3.8-flash'],
            'fallback':[],'dailyBudgetCny':10,'allowFallback':False,'rpm':8,'tpm':9000,'concurrency':1,**changes}


ROUTES={'data':[{'logical':'chat-default','primary':'qwen3.8-flash','secondary':'deepseek-flash'}]}


def test_logic_authorization_preserves_existing_budget_and_limits():
    result=logical_key_request(policy(),ROUTES)
    assert result['models']==['deepseek-flash','qwen3.8-flash']
    for field in ('dailyBudgetCny','fallback','allowFallback','rpm','tpm','concurrency'):
        assert result[field]==policy()[field]
    assert logical_key_request(policy(models=result['models']),ROUTES) is None


@pytest.mark.parametrize('change',[{'blocked':True},{'alias':'another-agent-dev'}])
def test_bootstrap_does_not_unblock_or_change_another_agent(change):
    with pytest.raises(ValueError): logical_key_request(policy(**change),ROUTES)
