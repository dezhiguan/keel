"""Check the requested evidence and abstention behavior from actual agent outputs."""


def task_evidence(expected, actual, tags):
    answer = str(actual or "")
    expected = expected or {}
    if isinstance(expected, str):
        return 1.0 if expected in answer else 0.0
    required = expected.get("contains") or []
    forbidden = expected.get("notContains") or []
    if isinstance(required, str):
        required = [required]
    if isinstance(forbidden, str):
        forbidden = [forbidden]
    return 1.0 if all(word in answer for word in required) and all(word not in answer for word in forbidden) else 0.0
