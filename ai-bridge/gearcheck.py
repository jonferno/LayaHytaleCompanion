"""Task 1: gear-question detection, split out of main.py so it is unit-testable.

Importing `main` boots FastAPI/sounddevice, so this predicate lives on its own.
The old regex in main.py was plural-blind (`\\bweapon\\b` never matched
"weapons"), so a plural question never injected the live pack and the model
invented an item. Every noun stem now takes an optional `s`/`'s`, and bare
tokens like `kit|loot|stuff|things?` are covered too.
"""
import re

GEAR_QUESTION_RE = re.compile(
    r"\b(inventor(?:y|ies)|packs?|weapons?|armors?|shields?|pickaxes?|hatchets?|"
    r"swords?|axes?|battleaxes?|carrying|carry|holding|hold|held|hands?|equipped|"
    r"equipment|gear|wields?|wielding|pork|foods?|items?|kit|loot|stuff|things?|"
    r"having)\b",
    re.IGNORECASE,
)


def asks_about_gear(message: str) -> bool:
    return bool(GEAR_QUESTION_RE.search(message or ""))
