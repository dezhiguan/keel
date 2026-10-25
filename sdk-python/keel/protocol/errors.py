"""Public error codes generated from the keel/v1 contract."""

from keel._generated.errors import ErrorCode


class KeelError(Exception):
    def __init__(self, code: ErrorCode, message: str | None = None, *, meta: dict | None = None):
        self.code = code
        self.meta = _safe_meta(meta)
        super().__init__(message or code.message)


def _safe_meta(meta):
    """Only diagnostic evidence; credentials and arbitrary exception data never cross SSE."""
    if not isinstance(meta, dict):
        return {}
    import re
    out = {k: meta[k] for k in ('prompt_fallback', 'tool_attempts_complete') if type(meta.get(k)) is bool}
    if isinstance(meta.get('routing'), dict):
        from jsonschema import ValidationError
        from keel.routing import validate_routing
        try:
            out['routing'] = validate_routing(meta['routing'])
        except (ValueError, TypeError, ValidationError):
            pass  # Invalid diagnostics must not mask the original business error.
    if isinstance(meta.get('prompt_versions'), dict):
        out['prompt_versions'] = {k:v for k,v in meta['prompt_versions'].items()
                                  if isinstance(k,str) and re.fullmatch(r'[a-zA-Z0-9_/-]{1,120}',k) and type(v) is int and v>0}
    if isinstance(meta.get('tool_attempts'), list):
        attempts = []
        for row in meta['tool_attempts'][:20]:
            if not isinstance(row,dict) or not isinstance(row.get('tool'),str) or not re.fullmatch(r'[a-zA-Z0-9_.:-]{1,120}',row['tool']):
                continue
            params = row.get('parameters',{})
            attempts.append({'tool':row['tool'],'status':row.get('status') if row.get('status') in ('ok','failed','started') else 'started',
                             'parameters':{k:v for k,v in params.items() if k in ('query','env','id','page','size') and type(v) in (str,int,bool) and (not isinstance(v,str) or len(v)<=500)} if isinstance(params,dict) else {}})
        out['tool_attempts'] = attempts
    return out


__all__ = ["ErrorCode", "KeelError"]
