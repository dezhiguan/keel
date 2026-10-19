"""Meta-agent v2 ASGI entry and declared tool bindings."""

from pathlib import Path
from keel import Agent
from tools import catalog as catalog_store
from tools.manifest_check import check
from bridge import BridgeAuth
from intake import run, _requirement, _target, _dump, _parse, _draft, _resume, RULES

agent = Agent.from_manifest(Path(__file__).with_name("agent.yaml"))


@agent.tool(name="catalog.lookup")
def lookup_catalog(target_agent: str | None = None, version: int = 1) -> list[dict] | None:
    loaded = catalog_store.load()
    return None if loaded is None else list(loaded.values())


@agent.tool(name="manifest.validate")
def validate_manifest(manifest: dict, target_agent: str | None = None, version: int = 1) -> list[str]:
    return check(manifest, target_agent, catalog_store.load())


@agent.tool(name="scaffold.deliver")
def deliver_scaffold(work: dict, target_agent: str, version: int, job_id: str):
    from workflow import deliver
    return deliver(work)


agent.entry(run)
app = agent.asgi()
app.add_middleware(BridgeAuth)
