import re

_NAME_MAX = 24
_NAME_ALLOWED = re.compile(r"[^\w '\-\.]")   # letters, digits, space, ' - . and _


def sanitize_companion_name(raw: str, current: str) -> str:
    """Clean a companion name; empty/invalid -> keep `current`.

    Strips control characters and markup-ish characters (quotes, braces) that
    would break the plugin's naive JSON field scanner, collapses whitespace and
    caps the length.
    """
    s = str(raw or "").strip()
    if not s:
        return current
    s = _NAME_ALLOWED.sub("", s)
    s = re.sub(r"\s+", " ", s).strip()
    if not s:
        return current
    return s[:_NAME_MAX]


def coerce_talk_mode(raw: str) -> str:
    """`toggle` (any case) else `hold`."""
    return "toggle" if str(raw or "").strip().lower() == "toggle" else "hold"
