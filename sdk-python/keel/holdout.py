"""Score hidden cases into tag aggregates. The aggregate has no case text."""

from collections.abc import Callable
from typing import Any

from keel.eval.run import score_by_tag

Scorer = Callable[[Any, str, list[str]], float]


def aggregates(cases: list[dict[str, Any]], scorers: list[Scorer], answers: list[str]) -> list[dict[str, Any]]:
    rows = []
    counts: dict[str, int] = {}
    for case in cases:
        tags = case.get("tags") or ["untagged"]
        if not isinstance(tags, list) or not tags:
            tags = ["untagged"]
        rows.append({"expected": case.get("expected"), "tags": tags})
        for tag in tags:
            counts[str(tag)] = counts.get(str(tag), 0) + 1
    scores = score_by_tag(rows, scorers, answers)
    return [{"tag": tag, "score": score, "count": counts[tag]} for tag, score in scores.items()]
