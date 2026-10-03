# A real dataset needs at least 50 tagged rows covering delegate, denied, and budget.

def routed(expected, actual, tags):
    return 1.0 if actual == expected else 0.0
