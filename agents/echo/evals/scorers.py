def answer_matches(expected, actual, tags):
    if isinstance(expected, dict) and "contains" in expected:
        return float(str(expected["contains"]) in actual)
    return float(str(expected).strip() == actual.strip())
