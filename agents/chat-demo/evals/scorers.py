"""Smoke scorers. Each case's expected.contains must appear in the reply."""


def contains_expected(expected, answer: str, tags: list[str]) -> float:
    needle = expected.get("contains") if isinstance(expected, dict) else None
    if not needle:
        return 1.0 if (answer or "").strip() else 0.0
    return 1.0 if needle in (answer or "") else 0.0
