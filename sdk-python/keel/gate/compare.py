"""Tag scores versus the manifest gate. 0.02 regression is 2 points, matching keel-server."""


def judge(by_tag: dict[str, float], baseline: dict[str, float] | None, *, min_score: float, max_regression: float) -> tuple[bool, list[str]]:
    if not by_tag:
        return False, ["没有用例"]
    reasons: list[str] = []
    overall = sum(by_tag.values()) / len(by_tag)
    if overall < min_score:
        reasons.append(f"总分 {overall:.2f} 低于 {min_score:.2f}")
    limit = max_regression * 100 if 0 < max_regression <= 1 else max_regression
    drop = limit / 100
    for tag, score in by_tag.items():
        previous = None if baseline is None else baseline.get(tag)
        if previous is None:
            continue
        if score < previous - drop:
            reasons.append(f"{tag} 从 {previous:.2f} 降到 {score:.2f}，超过 {limit:.0f} 分")
    return not reasons, reasons
