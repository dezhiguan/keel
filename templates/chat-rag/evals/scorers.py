"""Deterministic scorers. A real dataset needs at least 50 tagged rows."""

def citation_present(expected, actual, tags):
    if "abstain" in tags:
        return 1.0 if actual == expected else 0.0
    return 1.0 if expected in actual else 0.0
