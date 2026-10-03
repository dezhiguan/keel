"""Starlette protocol adapter for a Keel agent."""

import asyncio
import inspect
import json
import logging
import uuid
from types import SimpleNamespace

import jsonschema
import yaml
from starlette.applications import Starlette
from starlette.requests import Request
from starlette.responses import JSONResponse, Response, StreamingResponse
from starlette.routing import Route

from keel.context import Context
from keel.manifest import _contract_file
from keel.protocol.errors import ErrorCode
from keel.protocol.events import ErrorEvent, FinalEvent, SuspendEvent
from keel.protocol.sse import encode_event

logger = logging.getLogger(__name__)
_END = object()
_INVOKE_SCHEMA = yaml.safe_load(_contract_file("invoke.openapi.yaml"))["components"]["schemas"]["InvokeRequest"]


def _error(code: ErrorCode, trace_id: str = "", run_id: str = "") -> dict:
    return {"code": code.value, "message": code.message, "trace_id": trace_id,
            "run_id": run_id, "retryable": code.retryable}


def _trace_id(request: Request) -> str:
    parts = request.headers.get("traceparent", "").split("-")
    if len(parts) == 4 and len(parts[1]) == 32:
        return parts[1]
    return uuid.uuid4().hex


def create_app(agent) -> Starlette:
    agent.validate_config()

    async def invoke(request: Request):
        trace_id = _trace_id(request)
        try:
            data = await request.json()
            if not isinstance(data, dict) or not isinstance(data.get("input"), dict):
                raise ValueError("request body must contain an input object")
            jsonschema.Draft202012Validator(_INVOKE_SCHEMA).validate(data)
        except (ValueError, json.JSONDecodeError, jsonschema.ValidationError):
            return JSONResponse(_error(ErrorCode.SERVER_INVALID_PARAM, trace_id),
                                status_code=ErrorCode.SERVER_INVALID_PARAM.http)
        if agent._entry is None:
            return JSONResponse(_error(ErrorCode.SERVER_INTERNAL_ERROR, trace_id), status_code=500)

        # TODO(P3-1): persist idempotency_key and return the original run on retries.
        run_id = uuid.uuid4().hex
        events: asyncio.Queue = asyncio.Queue()
        context = Context(agent.name, run_id, trace_id, events)
        entry_request = SimpleNamespace(**data)

        async def execute():
            try:
                result = agent._entry(entry_request, context)
                if inspect.isasyncgen(result):
                    terminal = False
                    async for event in result:
                        events.put_nowait(event)
                        if isinstance(event, (FinalEvent, SuspendEvent, ErrorEvent)):
                            terminal = True
                            break
                    if not terminal:
                        raise ValueError("entry stream ended without a terminal event")
                    return
                if inspect.isawaitable(result):
                    result = await result
                if isinstance(result, (FinalEvent, SuspendEvent, ErrorEvent)):
                    events.put_nowait(result)
                elif result is not None:
                    events.put_nowait(context.final(str(result)))
                else:
                    raise ValueError("entry returned no final or suspend event")
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.error("agent invocation failed trace_id=%s agent=%s", trace_id, agent.name)
                code = ErrorCode.SERVER_INTERNAL_ERROR
                events.put_nowait(ErrorEvent(**_error(code, trace_id, run_id)))
            finally:
                events.put_nowait(_END)

        async def stream():
            worker = asyncio.create_task(execute())
            try:
                while True:
                    try:
                        event = await asyncio.wait_for(events.get(), timeout=0.05)
                    except asyncio.TimeoutError:
                        if await request.is_disconnected():
                            break
                        continue
                    if event is _END:
                        break
                    try:
                        yield encode_event(event)
                    except Exception:
                        raise RuntimeError("SSE event encoding failed") from None
                    if isinstance(event, (SuspendEvent, FinalEvent, ErrorEvent)):
                        break
            except Exception:
                logger.error("SSE stream failed trace_id=%s agent=%s", trace_id, agent.name)
                code = ErrorCode.SERVER_INTERNAL_ERROR
                try:
                    yield encode_event(ErrorEvent(**_error(code, trace_id, run_id)))
                except Exception:
                    # Last-resort frame still uses the generated error-code metadata.
                    body = json.dumps(_error(code, trace_id, run_id), ensure_ascii=False)
                    yield f"event: error\ndata: {body}\n\n".encode("utf-8")
            finally:
                if not worker.done():
                    worker.cancel()
                await asyncio.gather(worker, return_exceptions=True)

        return StreamingResponse(stream(), media_type="text/event-stream")

    async def health(request: Request):
        return JSONResponse({"status": "ok" if agent._entry else "degraded", "agent": agent.name})

    async def manifest(request: Request):
        return JSONResponse({"manifest": agent.manifest.model_dump(mode="json"),
                             "version": agent.manifest.apiVersion.value, "sdkVersion": "0.1.0"})

    async def feedback(request: Request):
        try:
            data = await request.json()
            if not isinstance(data.get("trace_id"), str) or data.get("value") not in (-1, 1):
                raise ValueError("invalid feedback")
        except (ValueError, AttributeError):
            return JSONResponse(_error(ErrorCode.SERVER_INVALID_PARAM), status_code=400)
        # TODO(P0-7): forward feedback to the configured Langfuse project.
        return Response(status_code=204)

    async def run_not_found(request: Request):
        code = ErrorCode.RUN_NOT_FOUND
        return JSONResponse(_error(code), status_code=code.http)

    return Starlette(routes=[
        Route("/v1/invoke", invoke, methods=["POST"]),
        Route("/v1/health", health, methods=["GET"]),
        Route("/v1/manifest", manifest, methods=["GET"]),
        Route("/v1/feedback", feedback, methods=["POST"]),
        Route("/v1/runs/{run_id}/resume", run_not_found, methods=["POST"]),
        Route("/v1/runs/{run_id}", run_not_found, methods=["GET"]),
    ])


def mount_to(app, agent=None):
    if agent is None:
        from keel.agent import Agent
        agent = Agent._current
    if agent is None:
        raise ValueError("Create an Agent before calling mount_to")
    child = create_app(agent)
    existing = {route.path for route in app.routes}
    for route in child.routes:
        if route.path in existing:
            raise ValueError(f"Route conflict: {route.path}")
    for route in child.routes:
        app.add_route(route.path, route.endpoint, methods=route.methods)
    return app
