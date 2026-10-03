# A real dataset needs at least 50 tagged rows covering primary and aux.

def agent_name(expected, actual, tags):
    return 1.0 if actual == expected else 0.0
