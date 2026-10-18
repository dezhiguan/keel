import pytest

from migrate_model_key import rotation_request


def policy(**changes):
    return {"alias": "meta-agent-dev", "models": ["qwen-plus"], "blocked": False,
            "fallback": [], "allowFallback": False, "dailyBudgetCny": 10,
            "rpm": 8, "tpm": 9000, "concurrency": 1, **changes}


def test_model_migration_preserves_budget_limits_and_fallback():
    result = rotation_request(policy(), "qwen3.8-flash", None)
    assert result == {"alias": "meta-agent-dev", "models": ["qwen3.8-flash"],
                      "fallback": [], "allowFallback": False, "dailyBudgetCny": 10,
                      "rpm": 8, "tpm": 9000, "concurrency": 1}


def test_migration_is_idempotent_only_after_secret_was_saved():
    current = policy(models=["qwen3.8-flash"])
    assert rotation_request(current, "qwen3.8-flash", "qwen3.8-flash") is None
    assert rotation_request(current, "qwen3.8-flash", None) is not None


@pytest.mark.parametrize("changes", [{"blocked": True}, {"alias": "other-agent-dev"}])
def test_migration_cannot_unblock_or_change_another_agent(changes):
    with pytest.raises(ValueError):
        rotation_request(policy(**changes), "qwen3.8-flash", None)
