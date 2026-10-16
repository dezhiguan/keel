"""Acceptance checks use observable answer content, not the agent implementation."""

def expected_and_source(expected, actual, tags):
    answer = str(actual or "")
    needle = str(expected or "")
    if needle not in answer:
        return 0.0
    if "rag" in tags and "来源：" not in answer:
        return 0.0
    if "abstain" in tags and "来源：" in answer:
        return 0.0
    return 1.0
