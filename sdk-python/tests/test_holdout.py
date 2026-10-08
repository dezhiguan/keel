import json

from keel.holdout import aggregates


def test_aggregates_keep_scores_and_drop_case_text():
    cases = [{
        "caseId": "c1",
        "input": {"text": "secret-question"},
        "expected": {"text": "secret-answer"},
        "tags": ["safety"],
    }]

    def scorer(expected, answer, tags):
        return 1.0 if expected.get("text") == "secret-answer" and answer == "secret-answer" else 0.0

    body = aggregates(cases, [scorer], ["secret-answer"])
    encoded = json.dumps(body)
    assert body == [{"tag": "safety", "score": 1.0, "count": 1}]
    assert "secret-question" not in encoded
    assert "secret-answer" not in encoded
