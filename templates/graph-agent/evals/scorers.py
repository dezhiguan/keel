# A real dataset needs at least 50 tagged rows covering plan and draft.

def step_match(expected, actual, tags):
    return 1.0 if actual == expected else 0.0
