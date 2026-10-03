"""LangGraph checkpoint owned by the agent.

The platform stores only the ref string. SqliteSaver keeps the graph state in
`.keel/graph.sqlite`, so a new process can resume the same thread.
"""
import sqlite3
from pathlib import Path
from typing import TypedDict
from uuid import uuid4

from langgraph.checkpoint.sqlite import SqliteSaver
from langgraph.graph import END, START, StateGraph

DB = Path(".keel/graph.sqlite")


class GraphState(TypedDict):
    topic: str
    step: str
    notes: list


def plan(state: GraphState) -> dict:
    notes = list(state["notes"])
    notes.append("outline:" + state["topic"])
    return {"notes": notes, "step": "draft"}


def draft(state: GraphState) -> dict:
    notes = list(state["notes"])
    notes.append("answer:" + state["topic"])
    return {"notes": notes, "step": "done"}


def _app():
    DB.parent.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB, check_same_thread=False)
    graph = StateGraph(GraphState)
    graph.add_node("plan", plan)
    graph.add_node("draft", draft)
    graph.add_edge(START, "plan")
    graph.add_edge("plan", "draft")
    graph.add_edge("draft", END)
    return graph.compile(checkpointer=SqliteSaver(connection), interrupt_after=["plan"])


def start(topic: str) -> dict:
    return {"topic": topic, "step": "plan", "notes": []}


def advance(state: dict) -> dict:
    if state.get("step") == "done":
        raise ValueError("checkpoint is already done")
    app = _app()
    if state.get("ref"):
        config = {"configurable": {"thread_id": state["ref"]}}
        app.invoke(None, config)
    else:
        config = {"configurable": {"thread_id": "ckpt_" + uuid4().hex}}
        app.invoke({"topic": state["topic"], "step": "plan", "notes": []}, config)
    values = dict(app.get_state(config).values)
    values["ref"] = config["configurable"]["thread_id"]
    return values


def save(state: dict) -> str:
    if state.get("ref"):
        return state["ref"]
    return advance(state)["ref"]


def load(ref: str) -> dict:
    config = {"configurable": {"thread_id": ref}}
    values = dict(_app().get_state(config).values)
    values["ref"] = ref
    return values
