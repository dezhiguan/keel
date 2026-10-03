"""RagForge search client, using its existing /api/v1/search contract."""

import os
from dataclasses import dataclass

import httpx
from opentelemetry import trace
from opentelemetry.trace.propagation.tracecontext import TraceContextTextMapPropagator

from keel.protocol.errors import ErrorCode, KeelError
from keel.tracing import attrs


@dataclass(frozen=True)
class KnowledgeResult:
    results: list[dict]
    citations: list[dict]


class KnowledgeClient:
    def __init__(self, agent: str, tracer=None, http_client: httpx.AsyncClient | None = None):
        base_url = os.environ.get("KEEL_RAGFORGE_URL")
        token = os.environ.get("KEEL_RAGFORGE_TOKEN")
        if not base_url or not token:
            raise RuntimeError("KEEL_RAGFORGE_URL and KEEL_RAGFORGE_TOKEN are required")
        self.agent = agent
        self.tracer = tracer or trace.get_tracer("keel.knowledge")
        self.client = http_client or httpx.AsyncClient(
            base_url=base_url, headers={"Authorization": f"Bearer {token}"}, timeout=10)

    async def _kb_id(self, kb: str | int) -> int:
        if isinstance(kb, int) or (isinstance(kb, str) and kb.isdecimal()):
            return int(kb)
        # TODO(P1-18): allow SERVICE_ACCOUNT to resolve a readable KB by name.
        response = await self.client.get("/api/v1/knowledge-bases")
        self._check(response)
        for item in response.json().get("data", []):
            if item.get("name") == kb:
                return int(item["id"])
        raise KeelError(ErrorCode.SERVER_NOT_FOUND, "Knowledge base not found")

    @staticmethod
    def _check(response: httpx.Response) -> None:
        if response.status_code == 429:
            raise KeelError(ErrorCode.GW_QUOTA_EXCEEDED)
        if response.is_error:
            raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR)
        try:
            if response.json().get("code") != 200:
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR)
        except ValueError as exc:
            raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR) from exc

    async def search(self, kb: str | int, query: str, top_k: int = 8) -> KnowledgeResult:
        with self.tracer.start_as_current_span("knowledge.search", attributes={
            attrs.OBSERVATION_TYPE: "retriever", attrs.AGENT: self.agent,
            attrs.STATUS: "ok",
        }) as span:
            try:
                kb_id = await self._kb_id(kb)
                headers = {"X-Keel-Caller-Agent": self.agent}
                TraceContextTextMapPropagator().inject(headers)
                response = await self.client.post("/api/v1/search", json={
                    "kbIds": [kb_id], "query": query, "topK": top_k,
                    "caller_agent": self.agent,
                }, headers=headers)
                self._check(response)
                results = response.json()["data"]["results"]
                citations = [{"doc_id": hit.get("docId"), "chunk_id": hit.get("chunkId"),
                              "filename": hit.get("filename"), "score": hit.get("finalScore")}
                             for hit in results]
                return KnowledgeResult(results, citations)
            except httpx.TimeoutException as exc:
                span.set_attribute(attrs.STATUS, "failed")
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR) from exc
            except httpx.HTTPError as exc:
                span.set_attribute(attrs.STATUS, "failed")
                raise KeelError(ErrorCode.SERVER_INTERNAL_ERROR) from exc
            except KeelError:
                span.set_attribute(attrs.STATUS, "failed")
                raise

    async def aclose(self):
        await self.client.aclose()
