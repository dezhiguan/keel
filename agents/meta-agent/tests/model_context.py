"""Model-step fake using the real local prompt compiler."""
from pathlib import Path
from types import SimpleNamespace
from keel.prompts import Prompt


def model_context(llm, **values):
    async def prompt(name):
        body = (Path(__file__).resolve().parents[1] / 'prompts' / f'{name}.md').read_text()
        return Prompt(f'meta-agent/{name}', 1, 'text', False, {}, body)
    return SimpleNamespace(llm=llm, prompt=prompt, **values)
