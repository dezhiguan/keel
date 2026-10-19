"""Versioned model-step prompts and explicitly declared development skills."""

import json
from opentelemetry import trace

PROJECT_SKILL = "keel-project-development"


async def load_prompt(ctx, name):
    return await ctx.prompt(name)


async def project_guidance(ctx):
    """Use only the manifest-pinned, platform-authorized skill; no implicit grants."""
    manifest = getattr(ctx, "manifest", None)
    declared = getattr(getattr(manifest, "spec", None), "skills", None) or []
    if not any(item.name == PROJECT_SKILL for item in declared):
        return ""
    skill = await ctx.skills.load(PROJECT_SKILL)
    span = trace.get_current_span()
    span.set_attribute("keel.skill.name", skill.name)
    span.set_attribute("keel.skill.version", skill.version)
    ctx.project_skill_version = skill.version
    return "\n人工审核的项目开发技能：\n" + skill.instructions + "\n示例：\n" + json.dumps(skill.examples, ensure_ascii=False)
