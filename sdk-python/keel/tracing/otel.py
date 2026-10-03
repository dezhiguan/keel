"""OTel SDK export to Langfuse v4 using OTLP/HTTP protobuf."""

import logging
import os
from pathlib import Path

import httpx
from opentelemetry.exporter.otlp.proto.common.trace_encoder import encode_spans
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor, SpanExporter, SpanExportResult

from keel.tracing.buffer import TraceBuffer

logger = logging.getLogger(__name__)
INGESTION_VERSION_HEADER = "x-langfuse-ingestion-version"
INGESTION_VERSION = "4"
OTLP_PATH = "/api/public/otel/v1/traces"


class OtlpHttpSpanExporter(SpanExporter):
    def __init__(self, host: str, public_key: str, secret_key: str, buffer: TraceBuffer,
                 client: httpx.Client | None = None):
        self.endpoint = host.rstrip("/") + OTLP_PATH
        self.buffer = buffer
        self.client = client or httpx.Client(timeout=5)
        self.auth = (public_key, secret_key)
        self.buffer.start(self._send)

    def _send(self, payload: bytes) -> bool:
        try:
            response = self.client.post(
                self.endpoint, content=payload, auth=self.auth,
                headers={"content-type": "application/x-protobuf",
                         INGESTION_VERSION_HEADER: INGESTION_VERSION})
            return response.is_success
        except httpx.HTTPError:
            return False

    def export(self, spans) -> SpanExportResult:
        if not spans:
            return SpanExportResult.SUCCESS
        payload = encode_spans(spans).SerializeToString()
        if self._send(payload):
            return SpanExportResult.SUCCESS
        try:
            self.buffer.append(payload)
            return SpanExportResult.SUCCESS
        except OSError:
            logger.error("trace export and buffer failed trace_id= agent=")
            return SpanExportResult.FAILURE

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        self.buffer.replay(self._send)
        return self.buffer.pending() == 0

    def shutdown(self) -> None:
        self.buffer.close()
        self.client.close()


def configure_tracing(host: str, public_key: str, secret_key: str, buffer_path: str | Path,
                      *, client: httpx.Client | None = None) -> TracerProvider:
    exporter = OtlpHttpSpanExporter(host, public_key, secret_key, TraceBuffer(buffer_path), client)
    provider = TracerProvider(resource=Resource.create({"service.name": "keel-agent"}))
    provider.add_span_processor(BatchSpanProcessor(exporter, max_queue_size=2048))
    return provider


def configure_from_env() -> TracerProvider | None:
    host = os.environ.get("LANGFUSE_HOST")
    if not host:
        return None
    required = ("LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY", "KEEL_TRACE_BUFFER_PATH")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise RuntimeError(f"Missing required environment variable(s): {', '.join(missing)}")
    return configure_tracing(host, os.environ["LANGFUSE_PUBLIC_KEY"],
                             os.environ["LANGFUSE_SECRET_KEY"],
                             os.environ["KEEL_TRACE_BUFFER_PATH"])
