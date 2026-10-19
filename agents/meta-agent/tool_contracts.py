"""Runtime tool contracts shared by generation and static validation."""

TOOL_CONTRACTS = {
    "ci.log.fetch": {"kwargs": ["repo", "runId"], "types": {"repo": "repository slug string", "runId": "nonempty string (not int)"}, "returns": "dict: log (text), logsUnavailable (optional bool); repo is a keel-agents repository slug"},
    "git.pr.diff": {"kwargs": ["repo", "number"], "types": {"repo": "repository slug string", "number": "positive int, not str or bool; cast extracted PR digits with int()"}, "returns": "dict: diff (text), number"},
    "devflow.job.read": {"kwargs": ["jobId"], "returns": "list of job records; empty list means no evidence; jobId is uppercase DF-<digits>, extracted case-insensitively and normalized with upper() before the call and before comparing returned id or jobId"},
    "devflow.batch.read": {"kwargs": ["batchId"], "returns": "list of batch records; empty list means no evidence"},
    "devflow.catalog.search": {"kwargs": ["query"], "returns": "list[dict], JSON dictionaries (not SDK objects): name, access (READ/WRITE/EXEC), risk (LOW/MID/HIGH), description; read with item.get('access')/item.get('risk'), never getattr(item,...); query='' lists the catalog; normalize enum case when comparing"},
    "keel.gate.report.read": {"kwargs": ["jobId"], "returns": "list of gate report artifact metadata (sha256/ref), not a PASS verdict"},
    "shift.policy": {"kwargs": ["category"], "returns": "dict of shift policy guidance"},
}
