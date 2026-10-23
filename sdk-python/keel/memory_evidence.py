"""Privacy-safe semantic memory observations; raw material belongs to generations only."""
import json
from contextlib import contextmanager
from opentelemetry import trace
from keel.tracing.attrs import MEMORY_EVIDENCE

KEYS = frozenset('operation status version cursorId sourceIds count pending superseded gaps more sha256 offset totalChars used tokens budget'.split())


@contextmanager
def evidence(agent, env, operation, tracer=None):
    with (tracer or trace.get_tracer('keel.agent')).start_as_current_span('memory.'+operation, attributes={
        'langfuse.observation.type': 'retriever', 'keel.agent': agent, 'keel.env': env or '',
    }) as span:
        data = {'operation': operation, 'status': 'ok'}
        try:
            yield data
        except Exception:
            data['status'] = 'failed'
            raise
        finally:
            span.set_attribute('keel.status', 'ok' if data['status'] == 'ok' else 'fallback')
            span.set_attribute(MEMORY_EVIDENCE, json.dumps({k: v for k, v in data.items() if k in KEYS}, ensure_ascii=True))
