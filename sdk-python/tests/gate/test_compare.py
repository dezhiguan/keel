from keel.gate.compare import judge


def test_two_point_drop_fails_and_a_smaller_drop_passes():
    passed, reasons = judge({"read": 0.80}, {"read": 0.83}, min_score=0.5, max_regression=0.02)
    assert not passed
    assert "read" in reasons[0]
    passed, reasons = judge({"read": 0.82}, {"read": 0.83}, min_score=0.5, max_regression=0.02)
    assert passed
    assert reasons == []


def test_overall_below_the_minimum_fails_without_a_baseline():
    passed, reasons = judge({"read": 0.5, "approval": 0.5}, None, min_score=0.85, max_regression=2)
    assert not passed
    assert "总分" in reasons[0]


def test_empty_scores_fail():
    assert judge({}, None, min_score=0.85, max_regression=0.02) == (False, ["没有用例"])
