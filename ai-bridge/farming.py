"""Deterministic "farm" intent for the Laya bridge.

The model can emit ``task: farm`` on its own, but a repeat request ("plant any
seeds you have") used to come back as a stale-memory refusal with no task line,
exactly like the mining case that produced ``detect_task``. This module makes the
intent leg deterministic: an *imperative* farm verb plus something to farm, or an
explicit count, forces the task, and the message itself decides how many seeds.

Questions, negations and first-person narration never start a task, so "aren't you
bored of farming" and "I was planting seeds" stay conversation.

Pure: no I/O, no imports from ``main``, so ``test/farming_test.py`` can load it
without booting the bridge.
"""

import difflib
import re

# Exact tokens only: "plant" but not "planet", "hoe" but not "shoe".
FARM_VERBS = frozenset({
    "hoe", "hoes", "hoeing",
    "till", "tills", "tilling",
    "farm", "farms", "farming",
    "plant", "plants", "planting",
    "sow", "sows", "sowing",
    "cultivate", "cultivating",
})

# What makes a farm request a *job* rather than conversation. Crop words count
# here too, because "plant wheat" is a farm request with no literal "seeds".
FARM_OBJECTS = frozenset({
    "seed", "seeds", "area", "plot", "plots", "soil", "dirt", "ground",
    "crop", "crops", "field", "fields", "garden", "patch", "bed", "beds",
    "row", "rows", "farmland",
}) | frozenset({
    "wheat", "carrot", "carrots", "potato", "potatoes", "corn", "onion",
    "onions", "lettuce", "chilli", "chili", "chillies", "cotton", "mushroom",
    "mushrooms", "cauliflower", "aubergine", "tomato", "tomatoes", "pumpkin",
    "pumpkins", "cabbage", "rice", "beans", "garlic", "turnip", "turnips",
    "beetroot", "cucumber", "cucumbers",
})

# Plural/alternate spellings mapped to the single word the model already uses
# for crops, so the plugin gets one stable hint.
_CROP_ALIASES = {
    "carrots": "carrot",
    "potatoes": "potato",
    "tomatoes": "tomato",
    "onions": "onion",
    "chili": "chilli",
    "chillies": "chilli",
    "mushrooms": "mushroom",
    "pumpkins": "pumpkin",
    "turnips": "turnip",
    "cucumbers": "cucumber",
}
_CANONICAL_CROPS = tuple(sorted(
    {"wheat", "carrot", "potato", "corn", "onion", "lettuce", "chilli", "cotton",
     "mushroom", "cauliflower", "aubergine", "tomato", "pumpkin", "cabbage", "rice",
     "beans", "garlic", "turnip", "beetroot", "cucumber"}))
_CROP_WORDS = frozenset({
    "wheat", "carrot", "potato", "corn", "onion", "lettuce", "chilli", "cotton",
    "mushroom", "cauliflower", "aubergine", "tomato", "pumpkin", "cabbage",
    "rice", "beans", "garlic", "turnip", "beetroot", "cucumber",
}) | frozenset(_CROP_ALIASES)

_NUMBER_WORDS = {
    "one": 1, "two": 2, "three": 3, "four": 4, "five": 5,
    "six": 6, "seven": 7, "eight": 8, "nine": 9, "ten": 10,
}
_ALL_CUES = ("any", "all", "everything", "as many", "as much", "whatever",
             "the rest", "a few", "some")

# The plugin caps a patch at 5x5; asking for more is clamped here too so the ack
# and the plan cannot disagree.
MAX_COUNT = 400        # a stack is 100; she can plant from several stacks

_QUESTION_START = re.compile(
    r"^(what|why|how|who|where|when|which|whose|are|aren't|is|isn't|am|was|"
    r"were|do|does|did|don't|doesn't|didn't|can|could|would|will|should|"
    r"have|has|had|you|you're|youre|i|i'm|im|we|we're|they|he|she|it)\b")
_NEGATION = re.compile(r"\b(don'?t|do not|never|stop|quit|no more|not)\b")
# "a"/"an"/"single"/"one" marks a single plant even when the count is not written out.
_SINGLE_CUE = re.compile(r"\b(a|an|single|one)\b")
# "can you plant one carrot seed" is a REQUEST, not a question - strip the lead and
# keep parsing (measured 2026-10-25: the whole utterance was rejected, so the model
# supplied the intent with no count and she planted everything).
_REQUEST_LEAD = re.compile(r"^(?:can|could|would|will)\s+you\s+(?:please\s+)?")
_REQUEST_PREFIX = re.compile(
    r"^i\s+(want|need|would like)\s+you\s+to\s+")
_NARRATION = re.compile(r"^i\s+(was|am|'m|will be|had|have)\b")


def _tokens(text: str):
    return re.findall(r"[a-z']+", text)


def detect_farm(message: str) -> tuple[bool, int, str]:
    """Force a farm task from an imperative verb + a farm object or a count.

    Returns ``(is_farm, count, seed_hint)`` where ``count`` is 1..25 for an
    explicit number or number word, and ``0`` for "any"/"all"/unspecified (the
    plugin reads 0 as "every seed she has"). ``seed_hint`` is the crop named in
    the message, or "" for "whatever she has".
    """
    raw = (message or "").strip().lower()
    if not raw:
        return False, 0, ""
    if "?" in raw:
        return False, 0, ""
    text = re.sub(r"\s+", " ", raw)
    if _QUESTION_START.match(text) and not _REQUEST_PREFIX.match(text):
        lead = _REQUEST_LEAD.match(text)
        if lead:
            text = text[lead.end():]
        else:
            return False, 0, ""
    if _NARRATION.match(text):
        return False, 0, ""
    if _NEGATION.search(text):
        return False, 0, ""

    words = _tokens(text)
    if not any(w in FARM_VERBS for w in words):
        return False, 0, ""

    # Size/dimension spellings are not seed counts ("plant a 5x5 patch").
    clean = re.sub(r"\d{1,3}\s*[x?]\s*\d{1,3}", " ", text)
    count = 0
    digit = re.search(r"\b(\d{1,3})\b", clean)
    if digit:
        count = max(1, min(int(digit.group(1)), MAX_COUNT))
    else:
        for w in _tokens(clean):
            if w in _NUMBER_WORDS:
                count = _NUMBER_WORDS[w]
                break

    has_object = any(w in FARM_OBJECTS for w in words if w not in FARM_VERBS)
    has_count = count > 0 or any(cue in text for cue in _ALL_CUES)
    if not has_object and not has_count:
        # "plant yourself over there" is not a farm job.
        return False, 0, ""

    # Singular phrasing means ONE seed even with no number: "plant a carrot seed" is a
    # single plant, not "every carrot seed you own" (measured 2026-10-25: she planted
    # 13 carrots after being asked for one). Plural / any / all keeps 0 = every seed.
    if count == 0 and not any(cue in text for cue in _ALL_CUES):
        if _SINGLE_CUE.search(text) or "seed" in words:
            count = 1

    hint = ""
    for w in words:
        if w in _CROP_WORDS:
            hint = _CROP_ALIASES.get(w) or w
            break
    if not hint:
        # Tolerate a misheard or mistyped crop ("cauilflower"). Only words long enough to
        # be a real noun, and only a close match, so ordinary words never become crops.
        for w in words:
            if w in FARM_VERBS or len(w) < 5:
                continue
            near = difflib.get_close_matches(w, _CANONICAL_CROPS, n=1, cutoff=0.82)
            if near:
                hint = near[0]
                break
    return True, count, hint


# Which kind of seed the player asked for. "eternal" and "normal" plant the same crop
# but different seed items, and mixing them silently is wrong: a player with both wants
# to say which (the plugin also refuses and asks when it is ambiguous).
_ETERNAL_CUES = ("eternal", "eternals", "everlasting", "permanent")
_NORMAL_CUES = ("normal", "regular", "plain", "ordinary", "standard")


def seed_kind(message: str) -> str:
    """'eternal', 'normal', or '' when the player did not say."""
    words = set(re.findall(r"[a-z']+", (message or "").lower()))
    if words & set(_ETERNAL_CUES):
        return "eternal"
    if words & set(_NORMAL_CUES):
        return "normal"
    return ""


def count_from_message(message: str) -> int:
    """Seed count implied by the wording alone: 1 for singular, N for a number, else 0.

    Used when the MODEL supplies the farm task ("just seed" has no verb, so detect_farm
    never runs) - 0 would otherwise mean "plant every seed she owns" (measured
    2026-10-25: "just seed" planted the whole stack).
    """
    text = re.sub(r"\s+", " ", (message or "").lower().strip())
    if not text:
        return 0
    clean = re.sub(r"\d{1,3}\s*[x?]\s*\d{1,3}", " ", text)
    digit = re.search(r"\b(\d{1,3})\b", clean)
    if digit:
        return max(1, min(int(digit.group(1)), MAX_COUNT))
    words = _tokens(clean)
    for w in words:
        if w in _NUMBER_WORDS:
            return _NUMBER_WORDS[w]
    if any(cue in text for cue in _ALL_CUES):
        return 0
    if _SINGLE_CUE.search(text) or "seed" in words:
        return 1
    return 0


def is_kind_answer(message: str) -> bool:
    """A short reply that only names the seed kind: "normal", "the eternal ones"."""
    words = re.findall(r"[a-z']+", (message or "").lower())
    if any(w in FARM_VERBS for w in words):
        return False          # a full farm request: detect_farm owns it
    return bool(seed_kind(message)) and len(words) <= 5


def farm_ack(count: int, hint: str) -> str:
    """A short spoken acknowledgement for a forced farm task."""
    crop = (hint or "").strip().lower()
    if count and count > 0:
        n = int(count)
        noun = "seed" if n == 1 else "seeds"
        return f"On it - tilling a spot and planting {n} {crop + ' ' if crop else ''}{noun}."
    what = f"{crop} seeds" if crop else "seeds"
    return f"On it - tilling the ground and planting {what}."
