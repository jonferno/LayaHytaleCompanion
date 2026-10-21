"""Deterministic stance cues and spoken acks for the Laya bridge.

Cycle 12 + combat Task 1: the raw text decides the stance — the player's LAST
cue in the sentence wins ("follow me" must undo a stay, "stay here then
follow me" follows), and the spoken acknowledgement is a pure function of the
message, so typed chat and STT get the same reply every time.
"""

import random
import re
from typing import Optional

# Cycle 12: "follow me" must undo a stay. Last cue in the sentence wins, so
# "stay here then follow me" follows (the player's final instruction).
STAY_PATTERNS = (r"\bstay\b", r"\bwait\b", r"\bhold\b", r"\bstand\b", r"\bstop\b",
                 r"don'?t move", r"don'?t go")
FOLLOW_PATTERNS = (r"\bfollow\b", r"\bheel\b", r"come (with|here)", r"with me")
_CREATURE = (r"(bear|goblin|skeleton|zombie|wolf|wolves|boar|pig|cow|sheep|goat|"
             r"chicken|rabbit|deer|fox|spider|scarak|trork|kweebec|slime|bat|rat|"
             r"snake|crab|troll|ogre|imp|demon|undead|mummy|golem)")
ATTACK_PATTERNS = (r"\battack\b", r"\bkill\b", r"\bfight\b", r"take it out", r"deal with that",
                   rf"\b(get|grab|take out|deal with|hunt)\b[^.!?]{{0,30}}\b{_CREATURE}\b")
DEFEND_PATTERNS = (r"\bdefend\b", r"\bprotect\b", r"watch my back", r"\bguard me\b", r"\bcover me\b")

# Spec section 7 acks; attack/defend rotate through variants so repeated
# commands do not always produce the same line.
STANCE_ACKS = {
    "stay": ["Holding here.", "I'll hold this spot.", "Right here - call if you need me."],
    "follow": ["Right behind you.", "On your heels.", "With you - lead the way."],
    "attack": [
        "On it - I'll take the front.",
        "I've got the front.",
        "Moving in - keep behind me.",
        "Consider it handled.",
        "Target's mine - moving in.",
        "Let's dance. I'll press it.",
        "I'll carve it up - stay close.",
        "You point, I swing.",
        "Flanking now.",
        "Press it - I'm on the attack.",
    ],
    "defend": [
        "I've got your back.",
        "Nothing gets past me.",
        "Stay close - I'll hold the line.",
        "I'm right here with you.",
        "They'll have to get through me.",
        "I'll keep them off you.",
        "Shield up - you're covered.",
        "Back to back, then.",
    ],
}

# Randomize variants and never immediately repeat the previous line.
_ack_last = {}


def stance_intent(message: str) -> Optional[str]:
    """Explicit stance cue in the message, or None.

    Uses the LAST cue so a correction in the same breath wins. "stop mining"
    is a task cancel (handled before this by main.py's wants_stop_task guard)
    and must not read as a stay there.
    """
    text = (message or "").lower()
    best_pos = -1
    best = None
    for pattern, intent in [(p, "stay") for p in STAY_PATTERNS] + \
                           [(p, "follow") for p in FOLLOW_PATTERNS] + \
                           [(p, "attack") for p in ATTACK_PATTERNS] + \
                           [(p, "defend") for p in DEFEND_PATTERNS]:
        for m in re.finditer(pattern, text):
            if m.start() > best_pos:
                best_pos, best = m.start(), intent
    return best


def stance_ack(intent: str, message: str) -> str:
    """The ack for this stance, rotating through the variants per intent.

    Successive orders of the same kind get different lines; stay/follow have a
    single fixed line.
    """
    options = STANCE_ACKS[intent]
    last = _ack_last.get(intent)
    pool = [o for o in options if o != last] or options
    pick = random.choice(pool)
    _ack_last[intent] = pick
    return pick