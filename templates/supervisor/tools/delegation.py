"""In-process stand-in for ctx.delegate.

Undeclared names are refused. The child receives the same trace id and a
smaller X-Keel-Budget. Spent amount is deducted from the parent budget.
"""

CHILDREN = {
    "research-bot": lambda text: "notes:" + text,
    "draft-bot": lambda text: "draft:" + text,
}


def delegate(declared, name, text, budget, trace_id):
    if name not in declared:
        raise PermissionError(f"delegate not declared: {name}")
    if name not in CHILDREN:
        raise KeyError(name)
    if budget["steps"] < 1 or budget["cny"] <= 0:
        raise RuntimeError("budget exhausted")
    child_budget = {"cny": round(budget["cny"] - 0.1, 2), "steps": budget["steps"] - 1}
    result = CHILDREN[name](text)
    return {"result": result, "budget": child_budget, "trace_id": trace_id, "agent": name}
