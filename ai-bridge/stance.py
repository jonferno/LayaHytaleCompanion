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
_CREATURES = ("bear", "goblin", "skeleton", "zombie", "wolf", "wolves", "boar", "pig",
              "cow", "sheep", "goat", "chicken", "rabbit", "deer", "fox", "spider",
              "scarak", "trork", "kweebec", "slime", "bat", "rat", "snake", "crab",
              "troll", "ogre", "imp", "demon", "undead", "mummy", "golem", "squirrel",
              "raccoon", "hedgehog", "frog", "duck", "moose", "elk", "badger")
_CREATURE_SET = frozenset(_CREATURES)
_CREATURE = "(" + "|".join(_CREATURES) + ")"
_ANIMALS = frozenset({
    "bear", "wolf", "wolves", "boar", "pig", "cow", "sheep", "goat", "chicken",
    "rabbit", "deer", "fox", "squirrel", "raccoon", "hedgehog", "frog", "duck",
    "moose", "elk", "badger", "rat", "bat",
})
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

# Personality keys that may swear. The persona layer already tells the model to;
# the fast path never reaches the model, so the spoken ack has to carry the voice.
_STRONG_PERSONALITIES = frozenset({"potty-mouth", "spicy", "chaotic"})

_ATTACK_TARGET_SOFT = [
    "That {noun} picked the wrong day.",
    "I'll take the {noun}.",
    "One {noun}, coming up.",
    "Watch this - the {noun} is mine.",
    "The {noun} is going down.",
]
_ATTACK_TARGET_STRONG = [
    "The little {noun} is going down.",
    "This {noun} is fucked.",
    "I'm going to ruin that {noun}'s day.",
    "That {noun} picked the wrong fucking day.",
    "One dead {noun}, coming up.",
]
_ATTACK_MEAT = [
    "{Noun} meat is on the menu.",
    "{Noun} for dinner, then.",
]
_ATTACK_GENERIC_STRONG = [
    "Point me at it.",
    "Something is getting wrecked.",
    "I'm in the mood for violence.",
    "Say the word and it's dead.",
]


def _pick(key: str, options: list) -> str:
    """A variant that is not the one we just said."""
    last = _ack_last.get(key)
    pool = [o for o in options if o != last] or options
    pick = random.choice(pool)
    _ack_last[key] = pick
    return pick


# --- Task completion lines (2026-10-28) ---------------------------------------
# The post-task line used to be a fixed "Got N X - that's all the wood nearby",
# which repeated word for word. Each kind now gets a short remark, split by
# personality, and _pick stops the same one landing twice in a row.
_TASK_REMARKS = {
    "wood": {
        "soft": ["Good haul.", "That'll build something.", "Timber sorted.",
                 "Plenty of wood now.", "Enough to keep a fire going."],
        "strong": ["Good fucking haul.", "That's a proper pile of wood.",
                   "Timber sorted - next.", "Enough wood to burn this place down.",
                   "Wood's handled. Try to keep up."],
    },
    "mine": {
        "soft": ["Good seam.", "Ore's in the bag.", "Rock didn't argue.",
                 "Pick's still in one piece.", "That'll forge something."],
        "strong": ["Good fucking seam.", "Ore's in the bag.",
                   "Rock didn't argue. Smart rock.", "Pick's still in one piece.",
                   "That'll forge something worth swinging."],
    },
    "gather": {
        "soft": ["Basket's fuller.", "That's the easy part.", "Worth the walk.",
                 "Got what was worth taking."],
        "strong": ["Basket's fucking fuller.", "That's the easy part done.",
                   "Worth the walk, barely.", "Got what was worth taking."],
    },
    "farm": {
        "soft": ["Seeds are in the dirt.", "Now we wait.", "Planted and done.",
                 "That's the field started."],
        "strong": ["Seeds are in the fucking dirt.", "Now we wait, and you owe me.",
                   "Planted and done.", "That's the field started."],
    },
    "harvest": {
        "soft": ["Crop's in.", "Fresh from the field.", "That's dinner sorted.",
                 "Field's lighter now."],
        "strong": ["Crop's in the bag.", "Fresh from the field.",
                   "That's dinner sorted, you ungrateful bastard.",
                   "Field's lighter now."],
    },
}

_NO_MORE_LINES = {
    "wood": "That's all the wood nearby.",
    "mine": "That's all the ore nearby.",
    "gather": "That's everything nearby.",
}


def task_done_line(verb: str, got: int, item: str, total=None, personality: str = "default",
                   kind: str = "gather", reason: str = "", resource: str = "",
                   total_label: str = "in the pack now") -> str:
    """One short post-task line: what she got, the pack total, one remark.

    ``total`` is the number of that item now in her inventory (None when the
    inventory file could not be read). ``reason`` is ``no_more`` or ``stopped``.
    """
    strong = (personality or "").strip().lower() in _STRONG_PERSONALITIES
    head = f"{verb} {got} {item}"
    if total is not None and total > 0:
        head += f" - {total} {total_label}"
    head += "."
    if reason == "no_more":
        if resource:
            head += f" That's all the {resource} nearby."
        else:
            head += " " + _NO_MORE_LINES.get(kind, "That's everything nearby.")
    elif reason == "stopped":
        head += " Stopped there."
    pool = _TASK_REMARKS.get(kind, _TASK_REMARKS["gather"])
    remark = _pick(f"task-{kind}", pool["strong" if strong else "soft"])
    return head + " " + remark



def _cap(word: str) -> str:
    return word[:1].upper() + word[1:] if word else word


def _named_target(message: str) -> str:
    """The creature the player named, if any ("kill that squirell" is handled upstream)."""
    text = (message or "").lower()
    for word in _CREATURES:
        if re.search(rf"\b{re.escape(word)}\b", text):
            return "wolf" if word == "wolves" else word
    return ""


def _friendly_role(role) -> str:
    """Role name to a plain noun: Bear_Grizzly -> bear, Temple_Squirrel -> squirrel."""
    words = [w for w in re.split(r"[^a-z]+", str(role or "").lower()) if w]
    for word in words:
        if word in _CREATURE_SET:
            return "wolf" if word == "wolves" else word
    return ""


def _nearest_creature(state) -> tuple[str, float]:
    """Nearest named creature in the plugin snapshot, and its distance."""
    try:
        entities = (state or {}).get("nearby_entities") or []
        for entry in entities:
            if not isinstance(entry, dict):
                continue
            noun = _friendly_role(entry.get("type"))
            if not noun:
                continue
            try:
                dist = float(entry.get("distance"))
            except (TypeError, ValueError):
                dist = 99.0
            return noun, dist
    except Exception:
        pass
    return "", 99.0


def _laya_hp(state):
    """Her health from the snapshot, or None when unknown (the plugin sends -1)."""
    try:
        hp = float(((state or {}).get("laya") or {}).get("health"))
        return hp if hp > 0 else None
    except (TypeError, ValueError, AttributeError):
        return None


def attack_ack(message: str, state=None, personality: str = "default") -> str:
    """A situational line for an attack order.

    Names the creature the player named, else the nearest one in the area report, and
    adds one short modifier for distance or her own health. With nothing in sight it
    falls back to the generic attack lines.
    """
    strong = (personality or "").strip().lower() in _STRONG_PERSONALITIES
    noun = _named_target(message)
    near, dist = _nearest_creature(state)
    if not noun:
        noun = near
        if not near:
            dist = 99.0
    if not noun:
        options = list(_ATTACK_GENERIC_STRONG) if strong else list(STANCE_ACKS["attack"])
        return _pick("attack", options)

    pool = list(_ATTACK_TARGET_STRONG if strong else _ATTACK_TARGET_SOFT)
    if noun in _ANIMALS:
        pool += _ATTACK_MEAT
    line = random.choice(pool).format(noun=noun, Noun=_cap(noun))

    hp = _laya_hp(state)
    if hp is not None and hp < 40:
        prefix = "I'm hurting, but " if strong else "I'm hurt, but "
    elif near and noun == near and dist >= 15.0:
        prefix = "It's a walk, but "
    elif near and noun == near and dist <= 4.0 and random.random() < 0.3:
        prefix = "Close - " if strong else "Close. "
    else:
        prefix = ""
    return _pick("attack", [prefix + line])


def stance_ack(intent: str, message: str, state=None, personality: str = "default") -> str:
    """The ack for this stance, rotating through the variants per intent.

    Successive orders of the same kind get different lines; attack lines also name
    the creature from the message or the area report and match her personality.
    """
    if intent == "attack":
        return attack_ack(message, state, personality)
    return _pick(intent, list(STANCE_ACKS[intent]))


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