"""Deterministic output masking; retain a short tail for split SSE identifiers."""
import re


IDENTITY = re.compile(r"(?<!\d)([1-9]\d{5}(?:(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}[\dXx]|\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])\d{3}))(?![\dXx])")
PHONE = re.compile(r"(?<!\d)(?P<prefix>(?:\+?86|0086)[- ]?)?(?P<head>1[3-9]\d)(?P<sep1>[- ]?)(?P<middle>\d{4})(?P<sep2>[- ]?)(?P<last>\d{4})(?!\d)")
PATTERNS = (IDENTITY, PHONE)


def redact_pii(text: str) -> str:
    value = IDENTITY.sub(lambda m: m[0][:6] + '*' * (len(m[0]) - 10) + m[0][-4:], text)
    return PHONE.sub(lambda m: (m['prefix'] or '') + m['head'] + m['sep1'] + '****' + m['sep2'] + m['last'], value)


def safe_prefix_cut(text: str) -> int:
    cut = max(0, len(text) - 32)
    for pattern in PATTERNS:
        for match in pattern.finditer(text):
            if match.start() < cut < match.end():
                cut = match.start()
    return cut
