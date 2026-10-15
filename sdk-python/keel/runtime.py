"""Governed is the default. Standalone is a local mode and cannot run in production."""

import os

GOVERNED = "governed"
STANDALONE = "standalone"
_PRODUCTION_PROFILE = "prod"
_STANDALONE_ENVS = frozenset({"dev", "test", "staging"})


def mode() -> str:
    selected = os.environ.get("KEEL_MODE", "").strip() or GOVERNED
    if selected not in (GOVERNED, STANDALONE):
        raise RuntimeError("KEEL_MODE must be governed or standalone")
    if selected == STANDALONE and _production():
        raise RuntimeError("KEEL_MODE=standalone is forbidden in the production profile")
    return selected


def standalone() -> bool:
    return mode() == STANDALONE


def standalone_env() -> str:
    """Audit environment for standalone. Production is rejected by mode()."""
    selected = os.environ.get("KEEL_ENV", "").strip()
    if selected not in _STANDALONE_ENVS:
        raise RuntimeError("KEEL_ENV must be dev, test, or staging in standalone mode")
    return selected


def _production() -> bool:
    if os.environ.get("KEEL_ENV", "").strip() == _PRODUCTION_PROFILE:
        return True
    profiles = os.environ.get("SPRING_PROFILES_ACTIVE", "")
    return any(part.strip() == _PRODUCTION_PROFILE for part in profiles.split(",") if part.strip())
