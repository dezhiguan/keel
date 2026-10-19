"""Encode keel/v1 events as SSE frames."""

from keel.protocol.events import (ErrorEvent, FinalEvent, GuardrailEvent, StepEvent,
                                  SuspendEvent, TokenEvent, ToolEvent)

EVENT_NAMES = {
    GuardrailEvent: "guardrail",
    StepEvent: "step",
    ToolEvent: "tool",
    TokenEvent: "token",
    FinalEvent: "final",
    ErrorEvent: "error",
    SuspendEvent: "suspend",
}


def encode_event(event: GuardrailEvent | StepEvent | ToolEvent | TokenEvent | FinalEvent | ErrorEvent | SuspendEvent) -> bytes:
    name = EVENT_NAMES[type(event)]
    return f"event: {name}\ndata: {event.model_dump_json()}\n\n".encode("utf-8")
