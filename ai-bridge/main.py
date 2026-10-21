"""
Laya AI Bridge - FastAPI service running on 127.0.0.1:8788
Hybrid cloud approach, modeled on poker-coach's working pattern:
  - STT: Parakeet-tdt-0.6b-v2 (local CPU)
  - LLM: Groq (primary) + Google AI Studio (backup) via their respective APIs
  - TTS: Kokoro-82M (local GPU/CPU)
  - Decisions: laya.cpp typed-decisions (Vulkan) or rule-based fallback
"""

import base64
import asyncio
import codecs
import io
import json
import os
import random
import re
import struct
import subprocess
import tempfile
import threading
import time
from pathlib import Path
from typing import Awaitable, Callable, Optional

import httpx
import numpy as np
import soundfile as sf
from fastapi import FastAPI, HTTPException, Request
from fastapi.responses import HTMLResponse, JSONResponse, Response
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel

from stance import stance_intent, stance_ack, STANCE_ACKS

BASE_DIR = Path(__file__).resolve().parent
MODELS_DIR = BASE_DIR.parent / "models"
CONFIG_PATH = BASE_DIR.parent / "config" / "bridge-config.json"
LOG_PATH = BASE_DIR.parent / "logs" / "bridge.log"
VISION_LOG_PATH = BASE_DIR.parent / "logs" / "laya-vision.log"

# Cycle 12: the 20 character-creator fields, in protocol order.
SKIN_FIELDS = (
    "bodyCharacteristic", "underwear", "face", "eyes", "ears", "mouth",
    "facialHair", "haircut", "eyebrows", "pants", "overpants", "undertop",
    "overtop", "shoes", "headAccessory", "faceAccessory", "earAccessory",
    "skinFeature", "gloves", "cape",
)

app = FastAPI(title="Laya AI Bridge", version="0.4.0-hybrid")


@app.middleware("http")
async def no_cache(request, call_next):
    """Always serve fresh content - browsers were caching the old
    settings page and old /api/config values after fixes."""
    response = await call_next(request)
    response.headers["Cache-Control"] = "no-store"
    return response


def log(msg: str):
    """Write to both console and file so we can see what's happening."""
    import sys
    line = f"[Laya] {msg}"
    print(line, flush=True)
    try:
        LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        with open(LOG_PATH, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {msg}\n")
    except Exception:
        pass

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

def load_config():
    default = {
        "groq_api_key": os.getenv("GROQ_API_KEY", ""),
        "google_api_key": os.getenv("GOOGLE_API_KEY", ""),
        "zai_api_key": os.getenv("ZAI_API_KEY", ""),
        "zai_base_url": "https://api.z.ai/api/paas/v4",
        "zai_models": ["glm-4.5-flash", "glm-4.6"],
        "groq_models": ["openai/gpt-oss-120b", "qwen/qwen3.8-27b", "openai/gpt-oss-20b"],
        "google_model": "gemini-flash-latest",
        "llm_temperature": 0.8,
        "llm_max_tokens": 128,
        # Task 7: stream the Groq reply and start sentence-level TTS while text
        # is still arriving. Off by default (missing key = today's single-shot
        # path); set true to enable. Google and the typed-chat path are never
        # streamed.
        "llm_stream": False,
        "kokoro_model": str(MODELS_DIR / "kokoro-82m"),
        "tts_voice": "af_heart",
        "tts_speed": 1.0,
        # fp16 (default) or int8; int8 is ~half the size and usually faster on CPU.
        "tts_model": "fp16",
        # kokoro (local) or edge (Microsoft Edge online voices, free).
        "tts_engine": "kokoro",
        "edge_voice": "en-US-AvaNeural",
        # Task 7: master on/off for Laya's voice. Off silences every automatic
        # path (reply audio, background file, event reactions, streaming) while
        # the "Test voice" button still synthesizes on demand.
        "tts_enabled": True,
        "parakeet_model": str(MODELS_DIR / "parakeet-tdt-0.6b-v2"),
        "decision_engine": "laya",
        "laya_binary": str(BASE_DIR / "laya" / "laya-r-windows-amd64-vulkan.exe"),
        "laya_checkpoint": str(BASE_DIR / "laya" / "typed-decisions"),
        # Task 3: the bridge owns the resident laya.cpp server lifecycle.
        # Verified command line (Task 1) is plain --vulkan; no --tensor-core-fp32.
        "laya_server_exe": str(BASE_DIR / "laya" / "laya-r0004-windows-amd64-vulkan.exe"),
        "laya_server_args": [
            "--vulkan", "--server",
            "--host", "127.0.0.1", "--port", "8090",
            "--model", str(BASE_DIR / "laya" / "typed-decisions"),
        ],
        "persona_prompt": str(BASE_DIR.parent / "config" / "laya-persona.txt"),
        "voice_helper_enabled": True,
        "tp_key": "f8",
        # Plugin HTTP bridge surface the event reactions are pushed back to.
        "plugin_bridge_url": "http://127.0.0.1:8787/command",
        # ONNX engine paths (no-torch stack; see models/kokoro-onnx and models/parakeet-onnx)
        # Vision (Task 8): capture the player's screen on user turns and let
        # Gemini describe it in one line for prompt injection.
        "vision_enabled": True,
        "vision_describe_words": 50,
        "vision_model": "gemini-3.1-flash-lite",
        "vision_capture_cmd": "um win shot --exe HytaleClient.exe {out}",
        "vision_timeout_s": 8,
        "kokoro_onnx_model": str(MODELS_DIR / "kokoro-onnx" / "kokoro-v1.0.fp16.onnx"),
        "kokoro_onnx_voices": str(MODELS_DIR / "kokoro-onnx" / "voices-v1.0.bin"),
        "parakeet_onnx_dir": str(MODELS_DIR / "parakeet-onnx"),
    }
    if CONFIG_PATH.exists():
        try:
            with open(CONFIG_PATH, "r", encoding="utf-8") as f:
                user_cfg = json.load(f)
            default.update(user_cfg)
        except Exception as e:
            log(f"Warning: could not load config: {e}")
    return default

CFG = load_config()

# ---------------------------------------------------------------------------
# laya.cpp decision server client (Task 2)
# ---------------------------------------------------------------------------

# Resident HTTP server started by the bridge (Task 3 owns its lifecycle).
LAYA_SERVER_URL_DEFAULT = "http://127.0.0.1:8090"
# Warm /predict measured 41-44 ms (Task 1); 2 s covers a cold first call.
LAYA_TIMEOUT_S = 2.0
# Re-log an outage at most this often, so one outage is not 50 log lines.
LAYA_DOWN_RELOG_S = 60.0

# One process-wide event loop (daemon thread) that owns the shared
# httpx.AsyncClient. See LayaEngine._background_loop.
_LAYA_LOOP = None
_LAYA_LOCK = threading.Lock()

# ---------------------------------------------------------------------------
# laya.cpp server lifecycle (Task 3): the bridge starts the server at boot and
# tears the whole child tree down on graceful shutdown.
# ---------------------------------------------------------------------------

# Poll /health every 500 ms for at most this long (warm start measured ~2 s).
LAYA_START_POLL_S = 0.5
LAYA_START_BUDGET_S = 20.0
# The spawned server process (subprocess.Popen) while it is ours; None otherwise.
_LAYA_SERVER = None
# Set once the startup spawn has finished (ready or given up). DecisionHandler
# waits on this so the very first /chat or /health does not race a warming
# server and pin the engine to the rule fallback for the whole session.
_LAYA_START_DONE = threading.Event()


def _brief_error(e: Exception) -> str:
    """One short line for an exception, for outage logging."""
    text = str(e).strip() or e.__class__.__name__
    return " ".join(text.split())[:160]


def _decision_engine_is_laya() -> bool:
    """Task 4: is the laya.cpp engine selected?

    Only the exact value "rule-based" turns laya.cpp off. Anything else -
    including a missing key - keeps the previous behavior ("laya", try the
    server then fall back to rules), so an old config is not silently changed.
    """
    value = CFG.get("decision_engine", "laya")
    if isinstance(value, str):
        value = value.strip().lower()
    return value != "rule-based"


def _laya_server_log_path() -> Path:
    """Where the spawned server's stdout/stderr go (append, UTF-8)."""
    return LOG_PATH.parent / "laya-server.log"


def _laya_server_cmd() -> list:
    """Build the argv for the resident server from config.

    Falls back to the Task 1 proven defaults when the config keys are absent,
    so a config without the new keys still starts the same server.
    """
    exe = str(CFG.get("laya_server_exe") or "").strip()
    if not exe:
        exe = str(BASE_DIR / "laya" / "laya-r0004-windows-amd64-vulkan.exe")
    args = CFG.get("laya_server_args") or []
    if not args:
        url = str(CFG.get("laya_server_url", LAYA_SERVER_URL_DEFAULT))
        port = url.rsplit(":", 1)[-1].split("/")[0] or "8090"
        args = [
            "--vulkan", "--server",
            "--host", "127.0.0.1", "--port", port,
            "--model", str(BASE_DIR / "laya" / "typed-decisions"),
        ]
    return [exe] + [str(a) for a in args]


def _laya_server_health(url: str, timeout: float = 2.0):
    """One bounded GET {url}/health; returns the parsed object or None."""
    import httpx as _httpx
    try:
        r = _httpx.get(f"{url}/health", timeout=timeout)
        if r.status_code == 200:
            data = r.json()
            return data if isinstance(data, dict) else None
    except Exception:
        return None
    return None


def _start_laya_server() -> bool:
    """Spawn the resident laya.cpp server and wait for /health (Task 3.1).

    Best-effort: the bridge must NEVER fail to boot because of laya.cpp, so a
    missing exe, a failed spawn or a health timeout only logs one warn line and
    returns False (Task 2 leaves the rules fallback in place; no config edit
    here - the Task 4 gate decides whether the engine is used).

    Returns True only when the server is ours and /health answered.
    """
    global _LAYA_SERVER
    try:
        return _start_laya_server_locked()
    finally:
        # Unblock the first decision/health call regardless of outcome.
        _LAYA_START_DONE.set()


def _start_laya_server_locked() -> bool:
    global _LAYA_SERVER
    if _LAYA_SERVER is not None and _LAYA_SERVER.poll() is None:
        return True  # already ours and alive

    url = str(CFG.get("laya_server_url", LAYA_SERVER_URL_DEFAULT)).rstrip("/")

    # If something else already answers /health, do not spawn a second copy on
    # the same port - just respect it. If it is a stale server from a previous
    # run, START-LAYA.bat's kill filter cleans it on the next launch.
    existing = _laya_server_health(url, timeout=1.0)
    if existing is not None:
        log(f"laya.cpp server already listening on {url} "
            f"(backend={existing.get('backend')}); not spawning another")
        return False

    cmd = _laya_server_cmd()
    if not os.path.exists(cmd[0]):
        log(f"laya.cpp server not started: exe not found ({cmd[0]})")
        return False

    stdout = stderr = subprocess.DEVNULL
    try:
        LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        stdout = stderr = open(_laya_server_log_path(), "ab")
    except Exception:
        stdout = stderr = subprocess.DEVNULL

    kwargs = dict(stdout=stdout, stderr=stderr, stdin=subprocess.DEVNULL)
    if os.name == "nt":
        # DETACHED_PROCESS + NEW_PROCESS_GROUP: no console of its own, outlives
        # this process only until we taskkill it.
        kwargs["creationflags"] = (subprocess.DETACHED_PROCESS
                                   | subprocess.CREATE_NEW_PROCESS_GROUP)
    else:
        kwargs["start_new_session"] = True

    try:
        proc = subprocess.Popen(cmd, **kwargs)
    except Exception as e:
        log(f"laya.cpp server not started (spawn failed: {_brief_error(e)})")
        return False

    _LAYA_SERVER = proc
    log(f"laya.cpp server starting (pid {proc.pid}, {url})")

    deadline = time.time() + LAYA_START_BUDGET_S
    while time.time() < deadline:
        if proc.poll() is not None:
            log(f"laya.cpp server exited during start (code {proc.returncode}) - "
                "using rule fallback")
            _LAYA_SERVER = None
            return False
        health = _laya_server_health(url, timeout=2.0)
        if health is not None:
            backend = health.get("backend")
            model = health.get("model")
            log(f"laya.cpp server ready (backend {backend}) "
                f"pid={proc.pid} model={model} url={url}")
            return True
        time.sleep(LAYA_START_POLL_S)

    log(f"laya.cpp server not ready after {LAYA_START_BUDGET_S:g}s - "
        "using rule fallback")
    return False


def _stop_laya_server():
    """Terminate the server tree we started (Task 3.2).

    Same taskkill /T /F pattern as _kill_capture_tree: the server may itself
    have children, and /T takes the whole tree. Safe to call twice and from
    atexit / uvicorn shutdown.
    """
    global _LAYA_SERVER
    proc = _LAYA_SERVER
    _LAYA_SERVER = None
    if proc is not None:
        try:
            if proc.poll() is None:
                _kill_capture_tree(proc.pid)
                try:
                    proc.wait(timeout=5)
                except Exception:
                    pass
                log(f"laya.cpp server stopped (pid {proc.pid})")
        except Exception as e:
            log(f"laya.cpp server stop failed ({_brief_error(e)})")

# ---------------------------------------------------------------------------
# Persona (system prompt for Gelma)
# ---------------------------------------------------------------------------

def load_persona():
    persona_path = CFG["persona_prompt"]
    if os.path.exists(persona_path):
        with open(persona_path, "r", encoding="utf-8") as f:
            return f.read().strip()
    return """You are Laya, a loyal AI companion in the game Hytale. You speak concisely, with warmth and a touch of dry humor. You are in the world with the player, watching their back. When you speak, you also decide what to do: follow, attack, stay, or move. Always include a "say" line (what you say out loud) and an "action" line (what you do). Keep responses under 2 sentences."""

PERSONA = load_persona()

# ---------------------------------------------------------------------------
# Persistent memory + Hytale game knowledge
# ---------------------------------------------------------------------------

KNOWLEDGE_PATH = BASE_DIR.parent / "config" / "hytale-knowledge.json"
MEMORY_PATH = BASE_DIR.parent / "logs" / "laya-memory.json"

KNOWLEDGE = []
try:
    KNOWLEDGE = json.loads(KNOWLEDGE_PATH.read_text(encoding="utf-8"))["entries"]
    log(f"Knowledge base loaded: {len(KNOWLEDGE)} entries")
except Exception as e:
    log(f"Knowledge base not loaded ({e}) - run build_knowledge.py")


def load_memory():
    try:
        return json.loads(MEMORY_PATH.read_text(encoding="utf-8"))
    except Exception:
        return {"facts": [], "chat": []}


MEMORY = load_memory()


def save_memory():
    try:
        MEMORY_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = MEMORY_PATH.with_suffix(".tmp")
        tmp.write_text(json.dumps(MEMORY, indent=1), encoding="utf-8")
        os.replace(tmp, MEMORY_PATH)
    except Exception as e:
        log(f"Memory save failed: {e}")


def retrieve_knowledge(message: str, limit: int = 3) -> list:
    """Keyword-retrieve the knowledge entries most relevant to the message."""
    msg = message.lower()
    msg_words = set(re.findall(r"[a-z]+", msg))
    scored = []
    for entry in KNOWLEDGE:
        kws = entry.get("keywords", [])
        hits = sum(1 for kw in kws if (len(kw) > 3 and kw in msg) or kw in msg_words)
        if hits:
            scored.append((hits, len(kws), entry.get("text", "")))
    scored.sort(key=lambda x: (-x[0], x[1]))  # most hits, then narrowest entry
    return [t for _, _, t in scored[:limit]]


_TRANSIENT_FACT = re.compile(
    r"\b(currently|right now|target is|gathering|mining|chopping|mined|gathered)\b",
    re.IGNORECASE)


def memory_block() -> str:
    """Format Laya's persistent memory for prompt injection.

    Task events and task progress are NOT conversation. They used to be injected
    as if the player said them ("observed: mined 4 stone"), which made unrelated
    chat answers reference old mining counts. Only real player/Laya exchanges and
    stable facts belong here.
    """
    parts = []
    facts = [f for f in MEMORY.get("facts", [])[-20:]
             if not _TRANSIENT_FACT.search(str(f))]
    if facts:
        parts.append("Facts Laya remembers:\n" + "\n".join(f"- {f}" for f in facts))
    chat = [c for c in MEMORY.get("chat", [])[-12:]
            if c.get("p") and not str(c.get("p", "")).startswith("(observed:")]
    if chat:
        lines = [f"Player: {c.get('p','')}\nLaya: {c.get('l','')}" for c in chat[-8:]]
        parts.append("Recent conversation:\n" + "\n".join(lines))
    return "\n\n".join(parts)


# Cycle 9: the plugin rewrites this file inside a second of any change (manual
# drag, gift, pickup, reward), so every turn sees her REAL pack - she used to
# claim she had no axe right after the player put one in her inventory.
INVENTORY_PATH = BASE_DIR.parent / "config" / "laya-inventory.json"
_INVENTORY_SECTIONS = ("hotbar", "storage", "tools", "backpack", "utility", "armor")
GEAR_QUESTION_RE = re.compile(
    r"\b(inventory|pack|weapon|armor|shield|pickaxe|hatchet|sword|axe|axes|"
    r"battleaxe|carrying|carry|holding|hold|held|hand|hands|equipped|"
    r"equipment|gear|wield|wielding|pork|food|item)\b",
    re.IGNORECASE,
)


def asks_about_gear(message: str) -> bool:
    return bool(GEAR_QUESTION_RE.search(message or ""))


def friendly_item_name(item_id: str) -> str:
    """Tool_Pickaxe_Iron -> Iron Pickaxe (best effort; ids are still readable)."""
    parts = [p for p in str(item_id).split("_") if p and p.lower() != "tool"]
    if parts and parts[0].lower() in ("weapon", "armor", "ingredient", "block", "food", "potion"):
        parts = parts[1:]
    materials = {"iron", "copper", "gold", "silver", "onyxium", "voidcrystal", "wood",
                 "stone", "bone", "linen", "fabric", "steel", "bronze", "mithril"}
    if len(parts) >= 2 and parts[-1].lower() in materials:
        parts = [parts[-1]] + parts[:-1]
    return " ".join(parts).title() if parts else str(item_id)


def inventory_block() -> str:
    """Read Laya's persisted pack and render it for the prompt. Never raises."""
    try:
        with open(INVENTORY_PATH, "r", encoding="utf-8") as f:
            data = json.load(f)
    except FileNotFoundError:
        return ""
    except Exception as e:
        log(f"Inventory read failed: {e}")
        return ""
    lines = []
    for section in _INVENTORY_SECTIONS:
        for it in data.get(section) or []:
            iid = it.get("id") or ""
            if not iid:
                continue
            qty = it.get("qty", 1)
            lines.append(f"- {friendly_item_name(iid)} x{qty}")
    if not lines:
        return "Laya's inventory right now: empty (she is not carrying anything)."
    return ("Laya's inventory right now (live; these are the items she actually "
            "carries and can use):\n" + "\n".join(lines))


def gear_block(state: dict, include_appearance: bool = False) -> str:
    """Render the live held item / armor / appearance from the plugin snapshot.

    This is deliberately separate from the pack dump: the held slot and the
    equipped armor are the facts the model was missing when it denied holding
    raw pork or ignored a request to ready the battleaxe.
    """
    laya = (state or {}).get("laya") or {}
    if not isinstance(laya, dict):
        return ""
    pieces = []
    held = str(laya.get("held_item") or "").strip()
    if held:
        slot = laya.get("held_slot")
        slot_text = f" in hotbar slot {slot}" if isinstance(slot, int) and slot > 0 else ""
        pieces.append(f"holding {friendly_item_name(held)}{slot_text}")
    else:
        pieces.append("hands empty")

    armor = laya.get("armor") or {}
    if isinstance(armor, dict):
        for key, label in (("head", "head"), ("chest", "chest"),
                           ("hands", "hands"), ("legs", "legs")):
            item = str(armor.get(key) or "").strip()
            if item:
                pieces.append(f"{label}: {friendly_item_name(item)}")

    offhand = str(laya.get("offhand") or "").strip()
    if offhand:
        pieces.append(f"offhand: {friendly_item_name(offhand)}")

    appearance = str(laya.get("appearance") or "").strip()
    if include_appearance and appearance:
        pieces.append(f"appearance: {appearance}")

    if not pieces:
        return ""
    return ("Laya's live equipment and appearance (authoritative; this is her "
            "actual current state): " + "; ".join(pieces) + ".")


def apply_stance_guard(message: str, action: str, say_line: str,
                       fast: bool = False) -> tuple[str, str]:
    """Apply a deterministic stance without discarding a real conversational reply.

    Short commands are already handled by fast_command() and keep their canned
    acknowledgement. For a longer sentence the model's line is the conversation;
    the guard may still change the action, but must not replace that line with
    "I've got your back." just because the text contains "defend".
    """
    if fast or action == "stop_task":
        return action, say_line
    stance = stance_intent(message)
    if not stance:
        return action, say_line
    action = stance
    if not (say_line or "").strip():
        say_line = stance_ack(stance, message)
    return action, say_line


# ---------------------------------------------------------------------------
# Validation error handler - logs exactly what was received
# ---------------------------------------------------------------------------

@app.exception_handler(RequestValidationError)
async def validation_exception_handler(request: Request, exc: RequestValidationError):
    log(f"=== VALIDATION ERROR on {request.method} {request.url.path} ===")
    log(f"Errors: {exc.errors()}")
    try:
        body = await request.body()
        log(f"Raw body: {body[:500]}")
    except Exception as e:
        log(f"Could not read body: {e}")
    return JSONResponse(
        status_code=422,
        content={"detail": exc.errors()},
    )

# REMOVED middleware - instead log body inside the endpoint to avoid body-stream issues

# ---------------------------------------------------------------------------
# Cloud LLM handlers (Groq primary, Google backup)
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Streaming helpers (Task 7)
# ---------------------------------------------------------------------------

# Characters that end a spoken sentence. Split happens when one of these is
# followed by whitespace (or is at the very end of the text so far).
_SENTENCE_ENDERS = ".!?"
# A sentence shorter than this that carries no letters/digits is dropped rather
# than spoken (guards against a stray "." or "..." fragment).
_MIN_SENTENCE_CHARS = 2


def _speakable_text(raw: str, final: bool = False) -> str:
    """Return only the text that would be *spoken* for a partially-received
    reply — i.e. ``clean_speech(raw)[0]`` computed on a prefix of the stream.

    This mirrors ``clean_speech``'s say-accumulation exactly (a ``say:`` line
    opens the speech block; every other control key closes it; plain lines
    inside the block are speech), so the streamed sentences and the final
    ``say_line`` cannot drift.

    While the reply is still arriving (``final=False``) the *trailing partial
    line* is held back when it is still a prefix of a control key (e.g. ``"emo"``
    -> ``emotion``, or a bare ``"say"`` about to become ``say:``), so a metadata
    word is never spoken even momentarily. At end of stream (``final=True``) the
    hold is released, so a genuine final word that happens to equal a control-key
    prefix is still spoken (it can no longer grow into a metadata line).
    """
    lines = (raw or "").splitlines()
    held_last = (not final) and bool(lines) and _is_control_prefix(lines[-1].strip())
    say_parts: list[str] = []
    in_say = False
    for idx, line in enumerate(lines):
        s = line.strip()
        # Hold the trailing partial line while it could still become a control
        # key — in BOTH the say block and the fallback below.
        if idx == len(lines) - 1 and held_last:
            continue
        key, sep, rest = s.partition(":")
        k = key.strip().lower() if sep else ""
        if k in _CONTROL_KEYS:
            rest = rest.strip()
            if k == "say":
                in_say = True
                if rest:
                    say_parts.append(rest)
            else:
                in_say = False
            continue
        if in_say and s:
            say_parts.append(s)
    say = " ".join(say_parts).strip()
    if not say:
        plain = [
            ln.strip() for ln in (raw or "").splitlines()
            if ln.strip() and not ln.strip().lower().startswith(_CONTROL_PREFIXES)
        ]
        if held_last and plain and plain[-1].lower() == lines[-1].strip().lower():
            plain = plain[:-1]
        say = " ".join(plain).strip()
    return say


def _is_control_prefix(s: str) -> bool:
    """True while ``s`` is a colon-less single token that is still a prefix of a
    control key (``"emo"`` -> ``emotion``) or exactly one (``"say"``). Used to
    hold the trailing partial line while the model is mid-token, so ``"say"``
    followed by ``"say: "`` cannot transiently count as speech. Multi-word lines
    (``"Say again."``) are never prefixes and are always kept."""
    if not s or s.endswith(":"):
        return False
    k = s.strip().lower()
    return bool(k) and any(ck.startswith(k) for ck in _CONTROL_KEYS)


def _split_sentences(text: str) -> tuple[list[str], str]:
    """Cut ``text`` into completed sentences on ``.!?`` followed by whitespace
    (or end of text). Returns ``(sentences, remainder)``; the remainder is the
    trailing text with no terminal boundary yet and is left for the next chunk.
    """
    sentences: list[str] = []
    start = 0
    n = len(text)
    i = 0
    while i < n:
        ch = text[i]
        if ch in _SENTENCE_ENDERS:
            # consume a run of enders ("!!", "...")
            j = i + 1
            while j < n and text[j] in _SENTENCE_ENDERS:
                j += 1
            if j < n and text[j].isspace():
                sent = text[start:j].strip()
                if sent:
                    sentences.append(sent)
                # skip the whitespace itself
                while j < n and text[j].isspace():
                    j += 1
                start = j
                i = j
                continue
            i = j
            continue
        i += 1
    return sentences, text[start:]


def _is_speakable(sentence: str) -> bool:
    """A completed sentence worth synthesizing: has a letter/digit."""
    return any(c.isalnum() for c in sentence) and len(sentence.strip()) >= _MIN_SENTENCE_CHARS


class _StreamTts:
    """Per-turn sentence-TTS state for the (groundwork) voice streaming path.

    Holds ONE turn's in-flight sentence clips, ordered by sentence index, and the
    asyncio tasks synthesizing them. It is instantiated inside ``run_chat`` and
    captured by the callback closure, so concurrent turns never share mutable
    state (no module globals).

    Note: as shipped, per-sentence synthesis is NOT used — capture.py can only
    play a single full clip, so a per-sentence path would add cost with no gain
    (see MODLOG). This class is retained as the groundwork for a future
    capture.py partial-audio channel.
    """

    def __init__(self):
        self._wavs: dict[int, bytes] = {}
        self._tasks: list = []
        self._next_idx = 0

    def spawn(self, sentence: str, emotion: str) -> None:
        """Synthesize one streamed sentence off the event loop and keep its WAV.

        Fire-and-forget: each sentence is synthesized concurrently while the rest
        of the reply is still arriving. Clips are keyed by sentence index so
        out-of-order completion cannot scramble playback; tasks are recorded so
        :meth:`stitch` can await outstanding ones. Failures log once and never
        fail the turn.
        """
        if not CFG.get("tts_enabled", True):
            return
        speed = EMOTION_SPEED.get(emotion, CFG["tts_speed"])
        idx = self._next_idx
        self._next_idx += 1

        async def _run():
            try:
                wav = await asyncio.get_event_loop().run_in_executor(
                    None, lambda: TTS.synthesize(sentence, voice=CFG["tts_voice"], speed=speed))
                if wav and len(wav) > 1000:
                    self._wavs[idx] = wav
            except Exception as e:
                log(f"Stream TTS error: {e}")

        self._tasks.append(asyncio.get_event_loop().create_task(_run()))

    async def stitch(self) -> Optional[bytes]:
        """Await outstanding sentence tasks and concatenate their clips in
        sentence order. Returns None if there is nothing usable (the caller then
        synthesizes the whole line as usual)."""
        if self._tasks:
            await asyncio.gather(*self._tasks, return_exceptions=True)
        ordered = [self._wavs[i] for i in sorted(self._wavs)]
        return _concat_wavs(ordered)


def _concat_wavs(wavs: list[bytes]) -> Optional[bytes]:
    """Concatenate PCM_16 WAV clips into one. Returns None on any mismatch so
    the caller falls back to a fresh full-line synthesis (never ships a broken
    clip)."""
    if not wavs:
        return None
    try:
        import wave as _wave
        frames: list[bytes] = []
        params = None
        for w in wavs:
            with _wave.open(io.BytesIO(w), "rb") as r:
                p = r.getparams()
                key = (p.nchannels, p.sampwidth, p.framerate, p.comptype)
                if params is None:
                    params = (key, p)
                elif key != params[0]:
                    return None
                frames.append(r.readframes(p.nframes))
        with io.BytesIO() as buf:
            with _wave.open(buf, "wb") as w:
                w.setparams(params[1])
                w.writeframes(b"".join(frames))
            return buf.getvalue()
    except Exception as e:
        log(f"WAV concat failed: {e}")
        return None


def _verify_stream_text(sentences: list[str], raw: str) -> None:
    """Requirement 2: prove the streamed sentences are exactly the spoken line.

    Compares the concatenation of the sentences handed to TTS against
    ``say_line`` from ``clean_speech`` of the same accumulated text, normalized
    (whitespace collapsed, casefolded). Logs both lengths and the boolean.
    """
    say_line, _a, _e = clean_speech(raw)
    streamed = " ".join(sentences)
    norm = lambda s: re.sub(r"\s+", " ", (s or "").strip()).casefold()
    match = norm(streamed) == norm(say_line)
    log(f"Stream text check: streamed_chars {len(norm(streamed))} "
        f"say_line_chars {len(norm(say_line))} match {match}")


class CloudLLMHandler:
    """Calls Groq (primary) or Google AI Studio (backup) via their respective APIs."""

    def __init__(self):
        self.http = httpx.AsyncClient(timeout=httpx.Timeout(8.0, connect=5.0))
        self.providers = []
        # Order = latency order: Groq qwen is fastest (0.4-1.7 s turns), z.ai
        # glm-4.5-flash second (1.9-2.8 s, free/unmetered), Google last.
        if CFG.get("groq_api_key"):
            self.providers.append({
                "name": "groq",
                "api_key": CFG["groq_api_key"],
                "models": CFG.get("groq_models", ["openai/gpt-oss-120b"]),
                "base_url": "https://api.groq.com/openai/v1",
                "chat_path": "/chat/completions",
            })
        if CFG.get("zai_api_key"):
            self.providers.append({
                "name": "zai",
                "api_key": CFG["zai_api_key"],
                "models": CFG.get("zai_models", ["glm-4.5-flash"]),
                "base_url": CFG.get("zai_base_url", "https://api.z.ai/api/paas/v4"),
                "chat_path": "/chat/completions",
            })
        if CFG.get("google_api_key"):
            self.providers.append({
                "name": "google",
                "api_key": CFG["google_api_key"],
                "model": CFG.get("google_model", "gemini-flash-latest"),
                "base_url": "https://generativelanguage.googleapis.com/v1beta",
                "model_path": "/models/",
                "generate_path": ":generateContent",
            })
        self.ready = len(self.providers) > 0
        self.cooldowns = {p["name"]: 0 for p in self.providers}

    async def generate(self, prompt: str, system_extra: str = "",
                       on_sentence: Optional[Callable[[str], Awaitable[None]]] = None,
                       ) -> str:
        """Return the full reply text. When ``on_sentence`` is given (and the
        Groq provider is tried), the reply is streamed and each *complete
        speakable sentence* is handed to ``on_sentence`` as soon as it exists, so
        the caller can start TTS before the whole reply has arrived. The text
        returned is byte-identical to the non-streaming call for the same reply.

        ``on_sentence`` receives only text that ``clean_speech`` would put in
        ``say`` (metadata lines are stripped before splitting), so nothing
        control-shaped can ever be spoken.
        """
        if not self.ready:
            return "(Laya's brain is offline - no API keys configured.)"

        now = time.time() * 1000
        avail = [p["name"] for p in self.providers if now >= self.cooldowns.get(p["name"], 0)]
        log(f"LLM generating - providers available={avail}")
        for provider in self.providers:
            name = provider["name"]
            if now < self.cooldowns.get(name, 0):
                log(f"  {name} skipping (cooling down until {self.cooldowns[name]})")
                continue

            try:
                log(f"  trying {name}...")
                if name == "zai":
                    result = await self._call_zai(provider, prompt, system_extra)
                elif name == "groq":
                    result = await self._call_groq(provider, prompt, system_extra,
                                                   on_sentence=on_sentence)
                elif name == "google":
                    result = await self._call_google(provider, prompt, system_extra)
                else:
                    continue

                if result:
                    self.cooldowns[name] = 0
                    log(f"  {name} -> SUCCESS")
                    return result
                # Keep a longer cooldown if the provider set one itself (z.ai
                # sets 1 h when the account is unfunded).
                self.cooldowns[name] = max(self.cooldowns.get(name, 0), now + 10000)
                log(f"  {name} -> failed, cooldown 10s")

            except Exception as e:
                log(f"  {name} exception: {e}")
                self.cooldowns[name] = max(self.cooldowns.get(name, 0), now + 10000)

        return "(Laya's thoughts are scattered - all LLM providers failed.)"

    async def _call_zai(self, provider: dict, prompt: str, system_extra: str = "") -> Optional[str]:
        """z.ai (Zhipu GLM) OpenAI-compatible chat, tried FIRST when configured.

        Deliberately non-streaming: this provider is the new primary but the
        streaming path is Groq-specific (flag off in live config anyway).
        An unfunded account answers HTTP 429 with code 1113 "Insufficient
        balance"; that is not a rate limit, so the provider is parked for an
        hour instead of being retried every turn.
        """
        system = PERSONA if not system_extra else f"{PERSONA}\n\n{system_extra}"
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt},
        ]
        for model in provider["models"]:
            try:
                resp = await self.http.post(
                    f"{provider['base_url']}{provider['chat_path']}",
                    headers={
                        "Authorization": f"Bearer {provider['api_key']}",
                        "Content-Type": "application/json",
                    },
                    json={
                        "model": model,
                        "messages": messages,
                        "temperature": CFG["llm_temperature"],
                        "max_tokens": CFG["llm_max_tokens"],
                        # GLM-4.5-flash is free but is a reasoning model; left on,
                        # it can spend the whole token budget on hidden reasoning
                        # and return empty content (measured 5 s, empty at 128).
                        "thinking": {"type": "disabled"},
                    },
                    timeout=httpx.Timeout(8.0, connect=5.0),
                )
                if resp.status_code == 200:
                    content = resp.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
                    if content:
                        log(f"zai {model}: answered")
                        return content
                    log(f"zai {model}: empty content")
                    continue
                body = resp.text[:300]
                if resp.status_code == 429 and ("Insufficient balance" in body or '"1113"' in body):
                    self.cooldowns[provider["name"]] = (time.time() * 1000) + 3600000
                    log("zai: account unfunded (insufficient balance) — cooldown 1h")
                    return None
                if resp.status_code in (401, 403):
                    log(f"zai {model}: auth failed ({resp.status_code})")
                    return None
                if resp.status_code == 404:
                    log(f"zai {model}: model not found (404)")
                    continue
                if resp.status_code == 429:
                    log(f"zai {model}: rate limited")
                    return None
                log(f"zai {model}: error {resp.status_code}: {body}")
            except Exception as e:
                log(f"zai {model} exception: {e}")
        return None

    async def _call_groq(self, provider: dict, prompt: str, system_extra: str = "",
                         on_sentence: Optional[Callable[[str], Awaitable[None]]] = None,
                         ) -> Optional[str]:
        system = PERSONA if not system_extra else f"{PERSONA}\n\n{system_extra}"
        messages = [
            {"role": "system", "content": system},
            {"role": "user", "content": prompt},
        ]

        stream_requested = bool(CFG.get("llm_stream")) and on_sentence is not None

        for model in provider["models"]:
            try:
                body = {
                    "model": model,
                    "messages": messages,
                    "temperature": CFG["llm_temperature"],
                    "max_tokens": CFG["llm_max_tokens"],
                    "stop": ["\nUser:", "\nPlayer:", "\n\n"],
                }
                if stream_requested:
                    body["stream"] = True
                    stream_ok = False   # HTTP stream established and consumed
                    # httpx must stream the response (client.stream), otherwise
                    # the body is buffered and every delta arrives at once.
                    try:
                        async with self.http.stream(
                            "POST",
                            f"{provider['base_url']}{provider['chat_path']}",
                            headers={
                                "Authorization": f"Bearer {provider['api_key']}",
                                "Content-Type": "application/json",
                                "Accept": "text/event-stream",
                            },
                            json=body,
                            timeout=httpx.Timeout(20.0, connect=5.0),
                        ) as resp:
                            if resp.status_code == 200:
                                stream_ok = True
                                text = await self._stream_groq(resp, model, on_sentence)
                                if text:
                                    return text.strip()
                                log(f"Groq {model}: empty stream")
                            elif resp.status_code in (401, 403):
                                log(f"Groq {model}: auth failed ({resp.status_code})")
                                return None
                            elif resp.status_code == 429:
                                log(f"Groq {model}: rate limited")
                                continue
                            elif resp.status_code == 404:
                                log(f"Groq {model}: model not found (404)")
                                continue
                            else:
                                await resp.aread()
                                log(f"Groq {model}: stream error {resp.status_code}: {resp.text[:150]}")
                    except Exception as e:
                        log(f"Groq {model}: stream request failed ({e})")
                    # If the stream completed but was genuinely empty (a
                    # reasoning model exhausting max_tokens), a second
                    # non-streaming call to the SAME model only repeats the same
                    # cost — skip straight to the next model. Retry non-streamed
                    # only when the stream never established.
                    if not stream_ok:
                        result = await self._call_groq_single(provider, model, messages)
                        if result:
                            return result
                    continue

                resp = await self.http.post(
                    f"{provider['base_url']}{provider['chat_path']}",
                    headers={
                        "Authorization": f"Bearer {provider['api_key']}",
                        "Content-Type": "application/json",
                    },
                    json=body,
                    timeout=8.0,
                )
                if resp.status_code == 200:
                    data = resp.json()
                    content = data.get("choices", [{}])[0].get("message", {}).get("content", "").strip()
                    if content:
                        return content
                elif resp.status_code == 404:
                    log(f"Groq {model}: model not found (404)")
                    continue
                elif resp.status_code in (401, 403):
                    log(f"Groq {model}: auth failed ({resp.status_code})")
                    return None
                elif resp.status_code == 429:
                    log(f"Groq {model}: rate limited")
                    continue
                else:
                    log(f"Groq {model}: error {resp.status_code}: {resp.text[:150]}")
                    continue
            except Exception as e:
                log(f"Groq {model} exception: {e}")
                continue

        return None

    async def _call_groq_single(self, provider: dict, model: str, messages: list) -> Optional[str]:
        """One non-streaming Groq call for a single model. Used only as the
        fallback when a stream yielded no text (error before any token)."""
        try:
            resp = await self.http.post(
                f"{provider['base_url']}{provider['chat_path']}",
                headers={
                    "Authorization": f"Bearer {provider['api_key']}",
                    "Content-Type": "application/json",
                },
                json={
                    "model": model,
                    "messages": messages,
                    "temperature": CFG["llm_temperature"],
                    "max_tokens": CFG["llm_max_tokens"],
                    "stop": ["\nUser:", "\nPlayer:", "\n\n"],
                },
                timeout=8.0,
            )
            if resp.status_code == 200:
                content = resp.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
                return content or None
            log(f"Groq {model}: fallback error {resp.status_code}")
        except Exception as e:
            log(f"Groq {model} fallback exception: {e}")
        return None

    async def _stream_groq(self, resp, model: str,
                           on_sentence: Optional[Callable[[str], Awaitable[None]]],
                           ) -> str:
        """Consume an OpenAI-compatible Groq SSE stream.

        Accumulates the reply text exactly as the non-streaming path would, and
        fires ``on_sentence`` for each complete *speakable* sentence as it
        completes. Returns the accumulated text (never raises: a transport error
        mid-stream returns what already arrived). Metadata lines (``emotion:``,
        ``action:``, ...) are fed through the same filter as ``clean_speech`` and
        are therefore never emitted.
        """
        buf = ""            # raw SSE buffer, may hold a partial line
        content = ""        # full reply, identical to non-streaming
        consumed = ""       # speakable text already handed to on_sentence
        desync_logged = False
        # Incremental decoder: a code point split across two byte chunks must not
        # be dropped, so the accumulated text stays byte-identical to the
        # non-streaming reply for non-ASCII too.
        decoder = codecs.getincrementaldecoder("utf-8")(errors="replace")

        try:
            async for chunk in resp.aiter_bytes():
                if not chunk:
                    continue
                buf += decoder.decode(chunk)
                while "\n" in buf:
                    line, buf = buf.split("\n", 1)
                    line = line.rstrip("\r")
                    if not line.startswith("data:"):
                        continue
                    payload = line[5:].strip()
                    if not payload or payload == "[DONE]":
                        continue
                    try:
                        event = json.loads(payload)
                    except Exception:
                        continue
                    choices = event.get("choices") or []
                    if not choices:
                        continue
                    piece = ((choices[0] or {}).get("delta") or {}).get("content")
                    if not piece:
                        continue
                    content += piece
                    # Re-filter the whole accumulated reply (the same rule as
                    # clean_speech); a mid-stream answer can only *grow* the
                    # spoken prefix while it is in say-mode. If it ever does not
                    # start with what we already spoke (a fallback-branch flip),
                    # stop streaming sentences rather than ever re-speaking or
                    # mis-speaking — the voice path still synthesizes the final
                    # say_line in full.
                    speakable = _speakable_text(content)
                    if not speakable.startswith(consumed):
                        if not desync_logged:
                            log(f"Groq {model}: stream speech desync -> stop early emits "
                                f"(consumed={consumed[-40:]!r} new={speakable[-40:]!r})")
                            desync_logged = True
                        consumed = speakable
                        continue
                    tail = speakable[len(consumed):]
                    sentences, rest = _split_sentences(tail)
                    for s in sentences:
                        if on_sentence is not None and _is_speakable(s):
                            await on_sentence(s)
                    consumed = speakable[:len(speakable) - len(rest)]
        except Exception as e:
            # Requirement 5: a stream error after partial text finishes with what
            # arrived; before any text the caller falls back to non-streaming.
            log(f"Groq {model}: stream interrupted ({e})")

        # Flush the remainder once the stream is done. final=True releases the
        # trailing-partial hold so a genuine last word is still spoken.
        content += decoder.decode(b"", final=True)
        speakable = _speakable_text(content, final=True)
        if speakable.startswith(consumed):
            tail = speakable[len(consumed):].strip()
            if tail and on_sentence is not None and _is_speakable(tail):
                await on_sentence(tail)

        return content

    async def _call_google(self, provider: dict, prompt: str, system_extra: str = "") -> Optional[str]:
        try:
            user_text = f"{PERSONA}\n\n{prompt}" if not system_extra else f"{PERSONA}\n\n{system_extra}\n\n{prompt}"
            body = {
                "contents": [{"parts": [{"text": user_text}]}],
                "generationConfig": {
                    "temperature": CFG["llm_temperature"],
                    "maxOutputTokens": CFG["llm_max_tokens"],
                },
            }

            resp = await self.http.post(
                f"{provider['base_url']}{provider['model_path']}{provider['model']}{provider['generate_path']}?key={provider['api_key']}",
                headers={"Content-Type": "application/json"},
                json=body,
                timeout=8.0,
            )
            if resp.status_code == 200:
                data = resp.json()
                candidates = data.get("candidates", [])
                if candidates:
                    parts = candidates[0].get("content", {}).get("parts", [])
                    if parts:
                        text = parts[0].get("text", "").strip()
                        if text:
                            return text
                log(f"Google {provider['model']}: empty response")
                return None
            elif resp.status_code in (401, 403):
                log(f"Google {provider['model']}: auth failed")
                return None
            elif resp.status_code == 404:
                log(f"Google {provider['model']}: model not found")
                return None
            else:
                log(f"Google {provider['model']}: error {resp.status_code}: {resp.text[:150]}")
                return None
        except Exception as e:
            log(f"Google {provider['model']} exception: {e}")
            return None

LLM = CloudLLMHandler()

# ---------------------------------------------------------------------------
# Vision (Task 8): screen capture -> Gemini one-line scene description
# ---------------------------------------------------------------------------

# Fix cycle 1: gemini-1.5-flash is retired (404 on generateContent).
# Fix cycle 4: gemini-flash-latest now points at gemini-3.8-flash, whose free
# tier is 20 requests/day — one exhausted it and took vision down for 15 h.
# gemini-3.1-flash-lite measured 2.7 s for a full description, and the fallback
# below covers that model's quota too (the limits are per model per day).
VISION_MODEL = "gemini-3.1-flash-lite"
VISION_FALLBACK_MODELS = ("gemini-3.5-flash-lite", "gemini-flash-latest")
VISION_KEEP = 20  # cache rotation: keep the last N (path, description) entries


def _kill_capture_tree(pid: int):
    """Kill a capture process AND its children (I-1).

    stdlib only reaps the shell on a timeout; on Windows that leaves a
    grandchild (e.g. um.exe) alive holding the inherited stdout/stderr pipes,
    which makes the reader block forever. taskkill /T /F takes the whole tree.
    """
    try:
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(pid)],
                       capture_output=True, timeout=5)
    except Exception:
        pass


def _capture_scene_png() -> Optional[str]:
    """Run the configured capture command, replacing {out} with a temp .png.

    Returns the PNG path on success, or None on any failure (logged as
    `vision skipped: <reason>`) so the chat turn is never blocked or failed.
    Every failure branch deletes the temp PNG (I-2); a timeout also kills the
    whole process tree (I-1b) so pipes and threads cannot accumulate.
    """
    cmd_tmpl = str(CFG.get("vision_capture_cmd") or "").strip()
    if not cmd_tmpl:
        log("vision skipped: vision_capture_cmd not configured")
        return None
    try:
        timeout = float(CFG.get("vision_timeout_s", 3) or 3)
    except Exception:
        timeout = 3.0
    try:
        fd, out_path = tempfile.mkstemp(prefix="laya-vision-", suffix=".png")
        os.close(fd)
    except Exception as e:
        log(f"vision skipped: temp file failed ({e})")
        return None

    def _discard():
        """I-2: best-effort removal of the temp PNG on every failure path."""
        try:
            if os.path.exists(out_path):
                os.unlink(out_path)
        except Exception:
            pass

    cmd = cmd_tmpl.replace("{out}", f'"{out_path}"')
    proc = None
    try:
        proc = subprocess.Popen(cmd, shell=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        proc.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        _kill_capture_tree(proc.pid)
        try:
            proc.communicate(timeout=2)  # drain/reap; tree is already dead
        except Exception:
            pass
        _discard()
        log(f"vision skipped: capture timed out after {timeout:g}s")
        return None
    except Exception as e:
        if proc is not None:
            _kill_capture_tree(proc.pid)
        _discard()
        log(f"vision skipped: capture failed ({e})")
        return None
    if proc.returncode != 0:
        _discard()
        log(f"vision skipped: capture exited {proc.returncode}")
        return None
    try:
        if not os.path.exists(out_path) or os.path.getsize(out_path) == 0:
            _discard()
            log("vision skipped: capture produced no image")
            return None
    except Exception as e:
        _discard()
        log(f"vision skipped: capture output check failed ({e})")
        return None
    return out_path


async def _describe_image(png_path: str, focus: str = "") -> Optional[str]:
    """Ask Gemini to describe the screenshot in one line (<= vision_describe_words).

    Reuses the bridge's existing Google key plumbing: the live `google` provider
    entry from LLM.providers (so a settings-page key change is picked up), with a
    CFG fallback. Any failure returns None after logging `vision skipped: ...`.
    """
    provider = next((p for p in LLM.providers if p.get("name") == "google"), None)
    if provider:
        key = provider.get("api_key", "")
        base_url = provider.get("base_url", "https://generativelanguage.googleapis.com/v1beta")
    else:
        key = CFG.get("google_api_key", "")
        base_url = "https://generativelanguage.googleapis.com/v1beta"
    if not key:
        log("vision skipped: no google_api_key for vision")
        return None
    try:
        payload = base64.b64encode(Path(png_path).read_bytes()).decode("ascii")
    except Exception as e:
        log(f"vision skipped: could not read capture ({e})")
        return None
    try:
        words = int(CFG.get("vision_describe_words", 80) or 80)
    except Exception:
        words = 80
    prompt = (
        f"Describe what you see in this Hytale game screenshot in ONE single line "
        f"of at most {words} words. Say what is in the CENTER of the view and "
        f"nearest to the player: terrain, water, structures, distinct creatures or "
        f"mobs by name (fox, goblin, ...), items, glowing effects, and anything "
        f"noteworthy. No preamble, no quotes, no line breaks."
    )
    focus = " ".join((focus or "").split())[:200]
    if focus:
        # Steer the describer to what was asked ("does this look like copper?")
        # without letting the player's text replace the description task.
        prompt += (
            f' The player just asked: "{focus}" - make sure the line covers '
            f'whatever in the view answers that, if anything does.'
        )
    def make_body(with_thinking_off: bool) -> dict:
        gen = {"temperature": 0.4, "maxOutputTokens": 512}
        # Flash models "think" by default and the thinking budget is charged
        # against maxOutputTokens; that truncated the one-line answer to a
        # fragment ("s arm/leg visible on the"). This task needs no reasoning.
        if with_thinking_off:
            gen["thinkingConfig"] = {"thinkingBudget": 0}
        return {
            "contents": [{"parts": [
                {"text": prompt},
                {"inlineData": {"mimeType": "image/png", "data": payload}},
            ]}],
            "generationConfig": gen,
        }

    # Model chain: Google free-tier quota is per model per day, so a single
    # exhausted/saturated model must not take vision down for the session.
    # 3.1-flash-lite was measured at 2.7 s and accepts thinkingConfig;
    # 3.5-flash-lite rejects thinkingConfig (400) but runs in ~1.9 s without it.
    models = []
    for m in (str(CFG.get("vision_model") or VISION_MODEL), *VISION_FALLBACK_MODELS):
        if m and m not in models:
            models.append(m)
    import asyncio as _asyncio
    describe_t0 = time.time()
    last_err = "no model configured"
    for model in models:
        thinking_off = "flash" in model
        for attempt in (1, 2):
            url = f"{base_url}/models/{model}:generateContent?key={key}"
            try:
                resp = await LLM.http.post(url, headers={"Content-Type": "application/json"},
                                           json=make_body(thinking_off), timeout=8.0)
            except Exception as e:
                last_err = f"request failed ({e})"
                resp = None
                break
            if resp.status_code == 200:
                try:
                    parts = (resp.json().get("candidates", [{}])[0]
                             .get("content", {}).get("parts", []))
                    text = "".join(p.get("text", "") for p in parts).strip()
                except Exception as e:
                    last_err = f"parse failed ({e})"
                    text = ""
                if text:
                    one_line = " ".join(" ".join(text.split()).split(" ")[:words]).strip()
                    if one_line:
                        if model != models[0]:
                            log(f"vision model fallback -> {model}")
                        log(f"vision describe: {time.time() - describe_t0:.1f}s ({model})")
                        return one_line
                last_err = "empty response"
                break
            if resp.status_code == 400 and thinking_off:
                # This variant rejects thinkingConfig; retry it once without.
                thinking_off = False
                last_err = "HTTP 400"
                continue
            if resp.status_code == 503 and attempt == 1:
                # Transient demand spike: one quick retry on the same model.
                await _asyncio.sleep(0.5)
                last_err = "HTTP 503"
                continue
            last_err = f"HTTP {resp.status_code}: {resp.text[:120]}"
            break
        if len(models) > 1 and model != models[-1]:
            log(f"vision model {model} unusable ({last_err}); trying next")
    log(f"vision skipped: {last_err}")
    return None


def _cache_vision(png_path: str, desc: str):
    """Append (path, description) to logs/laya-vision.log, keep the last 20.

    Rotation: read the JSON-lines file, keep the trailing VISION_KEEP lines, and
    rewrite via an atomic replace. Image BYTES are never written - only the temp
    PNG path. Temp PNGs that fall out of the window are deleted.
    """
    try:
        VISION_LOG_PATH.parent.mkdir(parents=True, exist_ok=True)
        lines = []
        if VISION_LOG_PATH.exists():
            lines = [ln for ln in VISION_LOG_PATH.read_text(encoding="utf-8").splitlines() if ln.strip()]
        entry = json.dumps({
            "ts": time.strftime("%Y-%m-%d %H:%M:%S"),
            "png": str(png_path),
            "desc": desc,
        }, ensure_ascii=False)
        all_lines = lines + [entry]
        dropped = all_lines[:-VISION_KEEP] if len(all_lines) > VISION_KEEP else []
        lines = all_lines[-VISION_KEEP:]
        tmp = VISION_LOG_PATH.with_suffix(".tmp")
        tmp.write_text("\n".join(lines) + "\n", encoding="utf-8")
        os.replace(tmp, VISION_LOG_PATH)
        for old in dropped:
            try:
                old_path = json.loads(old).get("png", "")
                if old_path and os.path.exists(old_path):
                    os.unlink(old_path)
            except Exception:
                pass
    except Exception as e:
        log(f"vision cache write failed: {e}")


VISION_TRIGGERS = (
    "look at this", "look at that", "look here", "look around", "take a look",
    "look through my eyes", "look through", "through my eyes", "see through my eyes",
    "see what i see", "look where i'm looking", "look where im looking",
    "look at me", "look at yourself", "look at your", "how do i look",
    "see this", "see that", "can you see", "do you see", "what do you see",
    "what's this", "what is this", "what's that", "what is that",
    "does this look", "does that look", "how does this look", "how does that look",
    "what's going on", "what is going on", "check this out", "check it out",
)


def wants_follow(message: str) -> bool:
    return stance_intent(message) == "follow"


def wants_stay(message: str) -> bool:
    """True when the player actually asked Laya to hold position.

    Fix cycle 4b: the persona forbids picking `stay` on her own, but the model
    still emitted it for "mine that copper" - which cleared the follow target,
    so a refused task left her parked. Anything without an explicit hold cue is
    treated as "keep following".
    """
    return stance_intent(message) == "stay"


# --- Cycle 5: deterministic task intent + count (repeat mining) -------------
# The model still emits task/target_hint, but a repeat request ("get all the
# copper again") used to come back as a stale-memory refusal with no task line.
# These helpers make the intent leg deterministic: a strong verb + a known hint
# word forces the task, and the message itself decides how many blocks.
BLOCKS_PATH = BASE_DIR.parent / "config" / "blocks.json"


def _load_hint_words():
    try:
        data = json.loads(BLOCKS_PATH.read_text(encoding="utf-8"))
        return sorted(data.get("hints", {}).keys())
    except Exception as e:
        log(f"blocks.json hints unavailable: {e}")
        return ["copper", "iron", "gold", "coal", "stone_ore"]


HINT_WORDS = _load_hint_words()
HINT_ALIASES = {
    "ore": "stone_ore",
    "shroom": "mushroom",
    "shrooms": "mushroom",
    "mushrooms": "mushroom",
    "tree": "wood",
    "trees": "wood",
    "log": "wood",
    "logs": "wood",
}
MINE_VERBS = ("mine", "mining", "dig", "digging")
GATHER_VERBS = ("gather", "collect", "harvest", "get", "pick up", "chop",
               "break", "smash", "cut", "fell", "cutting", "chopping")
MINE_HINTS = ("copper", "iron", "gold", "coal", "stone_ore",
              "stone", "stone ore", "cobblestone", "stone brick")
# Cycle 10b: chopping is tree-scoped. A bare "chop this tree" means the whole
# tree (the plugin anchors the repeat scan to the first block, radius 4.5);
# an explicit number ("just get 1") still wins.
WOOD_HINTS = ("wood", "oak", "birch", "spruce", "palm")
ALL_COUNT_CUES = ("everything", "as much", "as many", "around us", "around here",
                  "the area", "in the area", "until i say", "don't stop", "dont stop",
                  "keep going", "the rest")
STOP_TASK_CUES = ("stop mining", "stop gathering", "stop digging", "stop it",
                  "never mind", "nevermind", "nvm",
                  "stop chopping", "stop cutting", "stop breaking", "quit chopping",
                  "stop doing that", "why are you", "every time", "i didn't ask",
                  "didn't ask you",
                  "that's enough", "thats enough", "enough", "leave it",
                  "cancel that", "cancel the task", "no more", "don't mine",
                  "dont mine", "stop the task", "halt")


# Imperative task grammar. Only a task verb that *governs* the named resource
# starts a task: "mine copper" does, "aren't you getting bored of mining copper"
# and "I was mining copper" do not. This is the fix for conversational mentions
# being turned into orders.
TASK_VERBS = ("pick up", "mine", "dig", "gather", "collect", "harvest", "chop",
              "cut", "break", "smash", "fell", "get", "grab", "clear", "sweep")
TASK_GERUNDS = {
    "mining": "mine", "digging": "dig", "gathering": "gather",
    "collecting": "collect", "harvesting": "harvest", "chopping": "chop",
    "cutting": "cut", "breaking": "break", "smashing": "smash",
    "felling": "fell", "getting": "get", "grabbing": "grab",
}
# Words that may sit between the verb and the resource ("get me some copper").
_TASK_OBJECT_FILLERS = ("me", "us", "some", "the", "that", "this", "those",
                        "these", "all", "more", "a", "an", "any", "lot", "lots",
                        "of", "down", "up", "out")
_QUESTION_START = re.compile(
    r"^(what|why|how|who|where|when|which|whose|are|aren't|is|isn't|am|was|were|"
    r"do|does|did|don't|doesn't|didn't|can't|couldn't|won't|wouldn't|shouldn't|"
    r"have|has|had|you|you're|youre|i|i'm|im|we|we're|they|he|she|it)\b")
_NEGATIVE = re.compile(r"\b(don'?t|do not|never|not|stop|quit|no)\b")


def _task_imperative_rest(text: str):
    """Return the text after an imperative/request prefix, or None.

    Questions, first-person narration and negations are rejected outright. The
    remaining accepted shapes are terse orders ("mine copper", "go mine 5
    copper"), polite requests without a question mark ("can you mine copper"),
    "I want you to mine copper", "let's mine copper", and "start/go mining".
    """
    if not text or "?" in text:
        return None
    text = re.sub(r"\s+", " ", text.strip().lower())
    if _QUESTION_START.match(text) or _NEGATIVE.search(text):
        # "I want you to mine..." is a valid request despite starting with "I".
        if not re.match(r"^i\s+(want|need|would like)\s+you\s+to\s+", text):
            return None
    request = re.match(
        r"^(?:i\s+(?:want|need|would like)\s+you\s+to\s+|let'?s\s+|"
        r"(?:can|could|would|will)\s+you\s+(?:please\s+)?)\s*", text)
    rest = text[request.end():] if request else text
    # Polite/filler prefixes.
    while True:
        m = re.match(r"^(?:please|go|now|ok|okay|alright|just|hey|ay|yo|uh|um|so|well)\s+", rest)
        if not m:
            break
        rest = rest[m.end():]
    # "start mining" / "keep digging" / "go chopping".
    gerund = re.match(r"^(?:start|begin|keep)\s+(\w+)\b(.*)$", rest)
    if gerund and gerund.group(1) in TASK_GERUNDS:
        rest = TASK_GERUNDS[gerund.group(1)] + gerund.group(2)
    elif gerund:
        return None
    if not rest:
        return None
    return rest


_TASK_OBJECT_STOP = {
    "near", "by", "around", "from", "in", "at", "on", "with", "for", "to",
    "please", "then", "now", "over", "there", "here", "and", "or", "my",
    "your", "our", "me", "us", "it", "them", "this", "that", "these", "those",
}
_TASK_GENERIC_OBJECTS = {"thing", "things", "stuff", "something", "anything",
                         "block", "blocks", "item", "items", "object", "objects"}
_TASK_FILLERS = {"some", "the", "a", "an", "all", "more", "any", "lot", "lots",
                 "of", "me", "us", "that", "this", "those", "these"}


def _task_object_phrase(after_verb: str) -> str:
    """Best-effort noun phrase after an imperative verb ('stone rubble')."""
    words = []
    for raw in re.split(r"\s+", after_verb.strip()):
        w = re.sub(r"[^a-z0-9'-]", "", raw.lower())
        if not w:
            continue
        if w in _TASK_OBJECT_STOP:
            if words:
                break
            continue
        if w in _TASK_FILLERS or w.isdigit() or len(w) < 3:
            continue
        if w in _TASK_GENERIC_OBJECTS:
            continue
        words.append(w)
        if len(words) >= 3:
            break
    return " ".join(words)


CREATURE_WORDS = {
    "bear", "goblin", "skeleton", "zombie", "wolf", "wolves", "boar", "pig",
    "cow", "sheep", "goat", "chicken", "rabbit", "deer", "fox", "spider",
    "scarak", "trork", "kweebec", "slime", "bat", "rat", "snake", "crab",
    "troll", "ogre", "imp", "demon", "undead", "mummy", "golem",
}


def is_creature_attack(text: str) -> bool:
    t = (text or "").lower()
    words = set(re.findall(r"[a-z]+", t))
    return bool(words & CREATURE_WORDS) and bool(re.search(
        r"\b(kill|attack|fight|get|grab|take out|deal with|hunt)\b", t))


WOOD_SPECIES = {
    "ash", "aspen", "maple", "willow", "amber", "apple", "azure", "birch",
    "oak", "spruce", "palm", "beech", "cedar", "elm", "pine", "redwood",
    "mahogany", "teak", "walnut", "cherry", "crimson", "frost", "jade",
}


def detect_task(message: str):
    """Force a task from an *imperative* verb + a named resource.

    The task verb must directly govern the resource, with only determiners or a
    small number between them. That keeps conversational mentions such as
    "aren't you getting bored of mining copper" out of the task leg.
    """
    raw = (message or "").lower()
    # Spoken commands often put the verb last: "there is a stone here, mine it".
    if ("?" not in raw and not _QUESTION_START.match(raw)
            and not re.search(r"\b(don'?t|do not|never|stop)\b", raw)):
        trailing = re.search(
            r"(?:^|[,.;!]\s*)(mine|dig|gather|collect|harvest|chop|cut|fell|break)\b"
            r"[^.!?]{0,25}\b(it|that|this|them)\b", raw)
        if trailing:
            hints = list(HINT_WORDS) + list(HINT_ALIASES.keys())
            hit = next((w for w in sorted(hints, key=len, reverse=True)
                        if re.search(r"\b" + re.escape(w) + r"\b", raw)), None)
            if hit:
                hint = HINT_ALIASES.get(hit, hit)
                verb = trailing.group(1)
                task = "mine" if verb in ("mine", "dig") or hint in MINE_HINTS else "gather"
                return task, hint

    # A clear "chop/cut/fell ... trees/wood" wins even after a correction like
    # "not gather leaves chop trees"; a question or explicit "don't chop" does not.
    if ("?" not in raw and not _QUESTION_START.match(raw)
            and not re.search(
                r"\b(don'?t|do not|never|stop)\b[^.!?]{0,30}\b(chop|cut|fell)\b", raw)):
        if re.search(r"\b(chop|cut|fell)\b[^.!?]{0,40}\b(trees?|wood|logs?)\b", raw):
            return "gather", "wood"
    rest = _task_imperative_rest(message or "")
    if not rest:
        return None, None
    verb_alt = "|".join(re.escape(v) for v in sorted(TASK_VERBS, key=len, reverse=True))
    hints = list(HINT_WORDS) + list(HINT_ALIASES.keys())
    hint_alt = "|".join(re.escape(w) for w in sorted(hints, key=len, reverse=True))
    fillers = "|".join(re.escape(w) for w in _TASK_OBJECT_FILLERS) + r"|\d{1,3}"
    # verb [particle] [up to 3 fillers] hint
    pattern = re.compile(
        rf"^(?:{verb_alt})\b(?:\s+(?:{fillers})){{0,3}}\s+({hint_alt})\b")
    verb_match = re.match(rf"^({verb_alt})\b", rest)
    verb = verb_match.group(1) if verb_match else ""
    if not verb_match:
        return None, None   # "kill those goats" is a stance, never a task
    m = pattern.match(rest)
    if m:
        hint = HINT_ALIASES.get(m.group(1), m.group(1))
        task = "mine" if hint in MINE_HINTS else "gather"
        if verb in ("pick up", "get", "grab") and hint not in MINE_HINTS:
            task = "gather"
        return task, hint
    # Unknown noun phrase: let the plugin resolve it against the full block-name
    # table (e.g. "stone rubble" -> Rubble_Stone). Only imperative/request forms
    # reach this point, so conversation still never starts a task.
    phrase = _task_object_phrase(rest[verb_match.end():] if verb_match else rest)
    if phrase:
        if set(re.findall(r"[a-z]+", phrase.lower())) & CREATURE_WORDS:
            return None, None   # "get that goblin" is an attack, not a task
        task = "mine" if verb in ("mine", "dig") else "gather"
        return task, phrase
    return None, None


def derive_task_count(message: str, task: str, model_count=None, hint=None) -> int:
    """0 = every matching block in range; N = exactly N blocks."""
    if task not in ("mine", "gather"):
        return 1
    text = (message or "").lower()
    # Dimensions/radius are size, not item count ("25x25", "50 block radius").
    clean = re.sub(r"\d{1,3}\s*[x?]\s*\d{1,3}", " ", text)
    clean = re.sub(r"\d{1,3}\s*(?:block|blocks)?\s*radius", " ", clean)
    m = re.search(r"\b(\d{1,3})\b", clean)
    if m:
        n = int(m.group(1))
        if 1 <= n <= 64:
            return n
    if (re.search(r"\ball\b", text) or any(cue in text for cue in ALL_COUNT_CUES)
            or re.search(r"\b(area|radius)\b", text)):
        return 0
    if (hint or "").lower() in WOOD_HINTS:
        return 0   # "chop this tree" = this whole tree, not one block
    if model_count is not None:
        try:
            n = int(model_count)
            if 0 <= n <= 64:
                return n
        except (TypeError, ValueError):
            pass
    return 1


# Cycle 10c: "get all birch around" is an AREA request (everything within the
# scan radius), while "chop this tree" is TREE-scoped (the plugin anchors the
# rescan to the first block). Only wood needs the distinction; mining "all"
# has always meant the area around the player.
AREA_CUES = ("around", "nearby", "near here", "near us", "around me", "around us",
             "the area", "in the area", "area", "radius", "everything", "every one",
             "all the", "all of", "as much", "as many", "the rest", "whole")


def derive_task_radius(message: str, task: str, hint: str, count: int) -> int:
    """Requested clear radius in blocks. 25x25 area -> 12; 50 radius -> 50."""
    if task not in ("mine", "gather") or count != 0:
        return 0
    text = (message or "").lower()
    m = re.search(r"(\d{1,3})\s*[x?]\s*(\d{1,3})", text)
    if m:
        return max(4, min(32, int(round(max(int(m.group(1)), int(m.group(2))) / 2.0))))
    m = re.search(r"(\d{1,3})\s*(?:block|blocks)?\s*radius", text)
    if m:
        return max(4, min(32, int(m.group(1))))
    m = re.search(r"(\d{1,3})\s*(?:block|blocks)\s*(?:around|area)", text)
    if m:
        return max(4, min(32, int(m.group(1))))
    return 0


def derive_task_scope(message: str, task: str, hint: str, count: int):
    """'tree' | 'area' for wood tasks, None otherwise."""
    if task not in ("mine", "gather") or count != 0:
        return None
    text = (message or "").lower()
    if (hint or "").lower() not in WOOD_HINTS:
        return "area"   # ore/plant "all" = the area around the player
    if (any(cue in text for cue in AREA_CUES)
            or re.search(r"\d{1,3}\s*[x?]\s*\d{1,3}", text)
            or re.search(r"\d{1,3}\s*(?:block|blocks)?\s*radius", text)):
        log("Scope: area request -> scan every " + (hint or "wood") + " within the circle")
        return "area"
    # "chop this/that tree" stays on one tree even though it may take many blocks.
    if re.search(r"\b(this|that|the)\s+(tree|birch|oak|spruce|palm)\b", text):
        return "tree"
    if re.search(r"\ball\b", text):
        return "area"   # "get all birch" with no "this tree"
    return "tree"


_TASK_ACK_LAST = {}


def _pick_ack(options: list[str]) -> str:
    """Random choice that never immediately repeats the previous line."""
    last = _TASK_ACK_LAST.get("task")
    pool = [o for o in options if o != last] or options
    pick = random.choice(pool)
    _TASK_ACK_LAST["task"] = pick
    return pick


def task_ack(task: str, hint: str, scope, count: int) -> str:
    """A short, varied acknowledgement for a forced task.

    The task itself comes from the raw imperative text, so the ack does too. The
    options are randomized (not a fixed phrase) because repeating the same line
    every time is what makes her sound robotic.
    """
    h = (hint or "").lower()
    if task == "gather" and h in WOOD_HINTS:
        if scope == "tree":
            return _pick_ack([
                "On it - I'll drop this tree.",
                "Timber - this one is coming down.",
                "I'll take this tree apart.",
                "Chopping - stand clear.",
            ])
        if h in ("wood", ""):
            return _pick_ack([
                "On it - I'll clear the trees around here.",
                "I'll work the treeline nearby.",
                "Wood coming up - I'll clear the local trees.",
                "Right - I'll harvest what is around us.",
            ])
        return _pick_ack([
            f"On it - I'll clear the {h} around here.",
            f"I'll work the {h} nearby.",
            f"{h.title()} coming up - clearing the local trees.",
            f"Right - I'll harvest the {h} around us.",
        ])
    if task == "mine":
        if count and count > 0:
            return _pick_ack([
                f"On it - {count} {h or 'ore'} coming up.",
                f"{h.title() or 'Ore'} it is - pulling {count}.",
                f"Right - {count} {h or 'ore'}, then.",
                f"I'll have {count} {h or 'ore'} out of the rock.",
            ])
        if scope == "area":
            return _pick_ack([
                f"I'll sweep the {h or 'ore'} around here.",
                f"The local {h or 'ore'} is mine - back shortly.",
                f"Digging in - clearing the {h or 'ore'} nearby.",
                f"I'll work the {h or 'ore'} around us.",
            ])
        return _pick_ack([
            f"That {h or 'ore'} is mine.",
            f"I'll crack that {h or 'ore'} open.",
            f"One {h or 'ore'}, coming right up.",
            f"On it - I'll get that {h or 'ore'}.",
        ])
    if task == "gather":
        return _pick_ack([
            f"On it - I'll gather the {h or 'plants'} around here.",
            f"I'll collect the local {h or 'plants'}.",
            f"Right - the {h or 'plants'} around us, then.",
            f"Gathering - I'll bring back the {h or 'plants'}.",
        ])
    return _pick_ack(["On it - I'll get started.", "Right - moving.", "Consider it started."])


def wants_stop_task(message: str) -> bool:
    """Explicit cancel cue ("stop mining", "that's enough") -> cancel, keep following."""
    text = (message or "").lower()
    return any(cue in text for cue in STOP_TASK_CUES)


# --- Combat Task 1: instant deterministic command fast path ------------------
# A short, unambiguous utterance ("stop", "follow me", "stay here") needs no
# vision and no LLM: waiting ~3 s for a model round-trip only to have the
# deterministic guards overwrite its answer is pure latency. This path is
# deliberately NARROW - it must never swallow a real request (a task, a
# question, or ordinary chatter), because those still need the model.
#
# The stop phrases are a NARROW set: the whole utterance must be one of these.
# STOP_TASK_CUES is far too broad here - it contains conversational shards like
# "why are you" / "every time" that must still reach the LLM.
FAST_STOP_PHRASES = (
    "stop", "stop it", "stop that", "stop fighting", "stop attacking",
    "stop killing", "stop killing them", "stop killing these monsters",
    "never mind", "nevermind", "nvm", "halt", "enough", "that's enough",
    "thats enough",
)
# Task verbs: a message about work must go to the LLM/task leg, not the fast path.
FAST_TASK_WORDS = ("mine", "mining", "gather", "gathering", "chop", "chopping",
                   "dig", "digging", "cut", "cutting", "break", "breaking",
                   "collect", "collecting", "get", "getting", "harvest", "fell")
# Stop phrases are allowed to be a little longer ("stop killing these monsters"
# is 4 words); a stance cue must be terse - "follow the river to the north" is
# narration about a river, not an order, so longer stance-ish sentences fall
# through to the LLM. When in doubt, return None.
FAST_MAX_WORDS = 6
FAST_STANCE_MAX_WORDS = 4


def fast_command(message: str) -> Optional[tuple[str, str]]:
    """(action, say) for a pure deterministic command, else None.

    Shape rules (all must hold): lowercased + stripped, at most FAST_MAX_WORDS
    words, no "?" (a question is conversational). A narrow stop phrase wins;
    otherwise an explicit stance cue (stance_intent) decides, but only when the
    utterance is terse (FAST_STANCE_MAX_WORDS). Anything mentioning work (a task
    verb) is never fast-pathed.
    """
    text = (message or "").strip().lower()
    if not text or "?" in text:
        return None
    words = text.split()
    if len(words) > FAST_MAX_WORDS:
        return None
    # A task request ("mine 5 copper", "chop this tree") is never a command.
    if any(w in FAST_TASK_WORDS for w in words):
        return None
    stripped = re.sub(r"[^a-z0-9']+", " ", text).strip()
    if stripped in FAST_STOP_PHRASES or text in FAST_STOP_PHRASES:
        return ("stop_task", "Stopping.")
    if len(words) > FAST_STANCE_MAX_WORDS:
        return None
    stance = stance_intent(message)
    if stance:
        return (stance, stance_ack(stance, message))
    return None


def wants_vision(message: str) -> bool:
    """True when the player explicitly asks Laya to look at the screen.

    Vision costs ~2.5 s of capture plus a describe round-trip, so it is opt-in
    by phrase instead of running on every typed turn. Matching is
    case-insensitive and substring-based (covers "look at this thing").
    """
    text = (message or "").lower()
    return any(trigger in text for trigger in VISION_TRIGGERS)


def is_llm_failure(text: str) -> bool:
    """True for the sentinel replies generate() returns when no provider answered.

    These are status notices for the chat window, never something Laya should
    say out loud, so every TTS path skips them.
    """
    t = (text or "").strip().lower()
    return (t.startswith("(laya's brain is offline")
            or t.startswith("(laya's thoughts are scattered"))


async def capture_and_describe(user_text_turn: bool = False, focus: str = "") -> Optional[str]:
    """Capture the screen and return a one-line description, or None.

    Only user-originated turns *that ask her to look* capture: `user_text_turn`
    must be True (the caller in run_chat passes it for typed chat + voice) and
    the message must contain a cue from VISION_TRIGGERS. This function never
    raises - every failure logs `vision skipped: <reason>` and returns None.
    """
    if not user_text_turn:
        return None
    if not CFG.get("vision_enabled"):
        return None
    import asyncio as _asyncio
    try:
        bound = float(CFG.get("vision_timeout_s", 3) or 3) + 5.0
    except Exception:
        bound = 8.0
    loop = _asyncio.get_event_loop()
    capture_t0 = time.time()
    try:
        # I-1a: hard await bound. Even if the executor thread is wedged on
        # Windows pipe/grandchild behaviour, the turn itself is released here,
        # so added latency is structurally <= vision_timeout_s + 5s (+describe).
        png_path = await _asyncio.wait_for(
            loop.run_in_executor(None, _capture_scene_png), timeout=bound)
    except _asyncio.TimeoutError:
        log(f"vision skipped: capture timed out (hard bound {bound:g}s)")
        return None
    except Exception as e:
        log(f"vision skipped: capture failed ({e})")
        return None
    if not png_path:
        return None
    capture_s = time.time() - capture_t0
    desc = await _describe_image(png_path, focus)
    if desc:
        log(f"vision timing: capture {capture_s:.1f}s")
    if not desc:
        # I-2: capture succeeded but describe failed (Gemini outage is the
        # common path) - do not orphan a full screenshot on disk.
        try:
            os.unlink(png_path)
        except Exception:
            pass
        return None
    _cache_vision(png_path, desc)
    log(f"vision: {desc}")
    return desc

# ---------------------------------------------------------------------------
# Decision handler (laya.cpp Vulkan primary, rule-based fallback)
# ---------------------------------------------------------------------------

class DecisionHandler:
    """Task 2: readiness = the resident laya.cpp server answers GET /health.

    The engine talks HTTP to that server (see LayaEngine). No file-existence
    checks: the presence of a binary or checkpoint on disk says nothing about
    whether anything is listening on laya_server_url, and /health must tell the
    truth.
    """

    def __init__(self):
        self.engine = None
        self.engine_name = None
        self.ready = False
        # Task 4: the config off-switch is final for the session. When
        # decision_engine == "rule-based" we never build an HTTP client, never
        # spawn (Task 3 gate), never probe: engine_name stays truthful.
        self.laya_enabled = _decision_engine_is_laya()
        if not self.laya_enabled:
            self.engine = RuleBasedDecision()
            self.engine_name = "rule-based"
            self.ready = True
        # Single-flight: without this, two concurrent first calls (e.g. the
        # startup spawn is still warming while /health arrives) both see
        # ready=False and the loser can pin engine_name to "rule-based"
        # permanently even though the server came up.
        self._lock = threading.Lock()

    def ensure(self):
        if self.ready:
            return
        if not self.laya_enabled:
            # Config says rule-based: do not touch the server at all.
            with self._lock:
                if not self.engine_name:
                    self.engine = RuleBasedDecision()
                    self.engine_name = "rule-based"
                self.ready = True
            return
        with self._lock:
            if self.ready:  # another thread resolved it while we waited
                return
            self._resolve()

    def _resolve(self):
        # Task 3: if the bridge's own startup spawn is still warming, wait for
        # it (bounded by LAYA_START_BUDGET_S + slack) instead of racing it and
        # latching "rule-based" for the session.
        if not _LAYA_START_DONE.is_set():
            _LAYA_START_DONE.wait(timeout=LAYA_START_BUDGET_S + 3.0)
        engine = LayaEngine(CFG.get("laya_server_url", LAYA_SERVER_URL_DEFAULT))
        try:
            health = engine.health(timeout=2.0)
        except Exception as e:
            health = None
            log(f"laya.cpp health check failed ({_brief_error(e)})")
        if health is not None:
            self.engine = engine
            self.engine_name = "laya.cpp"
            self.ready = True
            log(f"laya.cpp server ready ({engine.url}, "
                f"model={health.get('model')}, backend={health.get('backend')})")
            return
        self.engine = RuleBasedDecision()
        self.engine_name = "rule-based"
        self.ready = True
        log("Using rule-based decision fallback")

    def decide(self, state_json: str, options: list[str]) -> str:
        self.ensure()
        return self.engine.decide(state_json, options)


class LayaEngine:
    """HTTP client for the resident laya.cpp decision server.

    Request/response shape observed in Task 1 (see MODLOG "Task 1 laya.cpp
    server proof"):

      POST {url}/predict
      {"state": <state object>, "questions": {"action": {"type": "choice",
       "instructions": "...", "criteria": {<option>: <option>, ...}}}}

      -> {"results": [{"answers": {"action": {"choice": <criteria key>,
           "probabilities": {...}, "confidence": ...}}}], "elapsed_ms": ...,
          "backend": "Vulkan0"}

    The winner is results[0].answers.action.choice; `.confidence` is NOT the
    winning probability. Any failure returns None so the caller can fall back.
    """

    def __init__(self, url: str):
        self.url = (url or LAYA_SERVER_URL_DEFAULT).rstrip("/")
        self._client = None          # shared httpx.AsyncClient, created lazily
        self._down_until = 0.0       # outage log guard (re-log at most 60 s)

    def health(self, timeout: float = 2.0) -> Optional[dict]:
        """One GET {url}/health. Synchronous, bounded, raises on any failure."""
        resp = httpx.get(f"{self.url}/health", timeout=timeout)
        resp.raise_for_status()
        return resp.json()

    def _get_client(self):
        # One AsyncClient per engine instance, created once on the process-wide
        # background loop and reused across every turn.
        if self._client is None or self._client.is_closed:
            self._client = httpx.AsyncClient(timeout=httpx.Timeout(LAYA_TIMEOUT_S))
        return self._client

    @staticmethod
    def _background_loop():
        """A process-wide event loop in a daemon thread.

        FastAPI runs /chat on its own loop; asyncio.run() cannot be nested
        there, and a fresh loop per call would defeat the shared client. This
        owns the long-lived loop the client is bound to.
        """
        global _LAYA_LOOP
        if _LAYA_LOOP is not None and not _LAYA_LOOP.is_closed():
            return _LAYA_LOOP
        with _LAYA_LOCK:
            if _LAYA_LOOP is None or _LAYA_LOOP.is_closed():
                loop = asyncio.new_event_loop()
                threading.Thread(target=loop.run_forever,
                                 name="laya-http", daemon=True).start()
                _LAYA_LOOP = loop
        return _LAYA_LOOP

    def _note_down(self, reason: str):
        """Log an outage at most once per LAYA_DOWN_RELOG_S seconds."""
        now = time.time()
        if now >= self._down_until:
            log(f"laya.cpp unavailable ({reason}) - using rule fallback")
            self._down_until = now + LAYA_DOWN_RELOG_S

    async def _decide_async(self, state_json: str, options: list[str]) -> str:
        state = json.loads(state_json)          # malformed state -> rule fallback
        body = {
            "state": state,
            "questions": {
                "action": {
                    "type": "choice",
                    "instructions": "What should Laya do next?",
                    "criteria": {o: o for o in options},
                }
            },
        }
        client = self._get_client()
        resp = await client.post(f"{self.url}/predict", json=body)
        if resp.status_code >= 400:
            # 422 = token budget, 5xx = server-side; both -> rule fallback.
            raise RuntimeError(f"HTTP {resp.status_code}: {resp.text[:200]}")
        data = resp.json()
        results = data.get("results") or []
        if not results:
            raise RuntimeError("response has no results")
        answers = results[0].get("answers") or {}
        choice = (answers.get("action") or {}).get("choice")
        if not isinstance(choice, str) or not choice:
            raise RuntimeError("response has no answers.action.choice")
        for opt in options:
            if opt.lower() == choice.strip().lower():
                return opt
        raise RuntimeError(f"unmatched choice {choice!r}")

    def decide(self, state_json: str, options: list[str]) -> str:
        # Run the HTTP call on the engine's persistent background loop, so the
        # shared AsyncClient is created once and reused. A new loop per call
        # (asyncio.run) would rebuild the client every turn.
        #
        # Fix (Important review): the "decisions ~10 ms" criterion is measured
        # here, at the real engine round trip, not at the run_chat call site -
        # the call site skips the engine for most turns (the persona/plugin
        # supplies the action), so its `decision` field is often 0-2 ms and
        # says nothing about laya.cpp. One greppable line per ACTUAL engine
        # call; the Turn timing `decision` semantics are unchanged.
        t0 = time.time()
        try:
            loop = self._background_loop()
            fut = asyncio.run_coroutine_threadsafe(
                self._decide_async(state_json, options), loop)
            choice = fut.result(timeout=LAYA_TIMEOUT_S + 1.0)
            log(f"laya.cpp decide {(time.time() - t0) * 1000:.0f}ms -> {choice}")
            return choice
        except Exception as e:
            log(f"laya.cpp decide {(time.time() - t0) * 1000:.0f}ms -> "
                f"fallback (rule-based)")
            self._note_down(_brief_error(e))
        return RuleBasedDecision().decide(state_json, options)


class RuleBasedDecision:
    def decide(self, state_json: str, options: list[str]) -> str:
        if "attack" in options and any(e in state_json for e in ("Goblin", "Skeleton", "Zombie")):
            return "attack"
        return "follow" if "follow" in options else (options[0] if options else "idle")

DECISION = DecisionHandler()

# ---------------------------------------------------------------------------
# TTS handler (Kokoro-82M, local)
# ---------------------------------------------------------------------------

# Kokoro v1.0 English voices: af_* = American female, am_* = American male,
# bf_* = British female, bm_* = British male.
KOKORO_VOICES = [
    {"id": "af_alloy",   "desc": "American female - balanced, neutral"},
    {"id": "af_aoede",   "desc": "American female - warm, mature"},
    {"id": "af_bella",   "desc": "American female - smooth, gentle"},
    {"id": "af_heart",   "desc": "American female - warm, kind (default)"},
    {"id": "af_jessica", "desc": "American female - bright, clear"},
    {"id": "af_kore",    "desc": "American female - soft, low"},
    {"id": "af_nicole",  "desc": "American female - friendly, mid"},
    {"id": "af_nova",    "desc": "American female - lively, young"},
    {"id": "af_river",   "desc": "American female - calm, flowing"},
    {"id": "af_sarah",   "desc": "American female - crisp, steady"},
    {"id": "af_sky",     "desc": "American female - airy, light"},
    {"id": "am_adam",    "desc": "American male - deep, steady"},
    {"id": "am_echo",    "desc": "American male - relaxed"},
    {"id": "am_eric",    "desc": "American male - clear, mid"},
    {"id": "am_fenrir",  "desc": "American male - gruff, low"},
    {"id": "am_liam",    "desc": "American male - smooth, young"},
    {"id": "am_michael", "desc": "American male - warm, mid"},
    {"id": "am_onyx",    "desc": "American male - deep, dark"},
    {"id": "am_puck",    "desc": "American male - bright, quick"},
    {"id": "am_santa",  "desc": "American male - Santa Claus style"},
    {"id": "bf_alice",  "desc": "British female - precise, mid"},
    {"id": "bf_emma",   "desc": "British female - soft, clear"},
    {"id": "bf_isabella", "desc": "British female - smooth, low"},
    {"id": "bf_lily",   "desc": "British female - light, young"},
    {"id": "bm_daniel", "desc": "British male - steady, mid"},
    {"id": "bm_fable",  "desc": "British male - storyteller"},
    {"id": "bm_george", "desc": "British male - warm, low"},
    {"id": "bm_lewis",  "desc": "British male - clear, young"},
]

EDGE_VOICES = [
    {"id": "en-US-AvaNeural",     "desc": "American female - natural, warm"},
    {"id": "en-US-AriaNeural",    "desc": "American female - expressive"},
    {"id": "en-US-JennyNeural",   "desc": "American female - friendly"},
    {"id": "en-US-EmmaNeural",    "desc": "American female - bright"},
    {"id": "en-US-AndrewNeural",  "desc": "American male - natural"},
    {"id": "en-US-BrianNeural",   "desc": "American male - steady"},
    {"id": "en-GB-SoniaNeural",   "desc": "British female - smooth"},
    {"id": "en-GB-LibbyNeural",   "desc": "British female - light"},
    {"id": "en-GB-RyanNeural",    "desc": "British male - clear"},
    {"id": "en-AU-NatashaNeural", "desc": "Australian female - warm"},
    {"id": "en-CA-ClaraNeural",   "desc": "Canadian female - soft"},
    {"id": "en-IE-EmilyNeural",   "desc": "Irish female - gentle"},
]

EDGE_VOICES = [
    {"id": "en-US-AvaNeural",     "desc": "American female - natural, warm"},
    {"id": "en-US-AriaNeural",    "desc": "American female - expressive"},
    {"id": "en-US-JennyNeural",   "desc": "American female - friendly"},
    {"id": "en-US-EmmaNeural",    "desc": "American female - bright"},
    {"id": "en-US-AndrewNeural",  "desc": "American male - natural"},
    {"id": "en-US-BrianNeural",   "desc": "American male - steady"},
    {"id": "en-GB-SoniaNeural",   "desc": "British female - smooth"},
    {"id": "en-GB-LibbyNeural",   "desc": "British female - light"},
    {"id": "en-GB-RyanNeural",    "desc": "British male - clear"},
    {"id": "en-AU-NatashaNeural", "desc": "Australian female - warm"},
    {"id": "en-CA-ClaraNeural",   "desc": "Canadian female - soft"},
    {"id": "en-IE-EmilyNeural",   "desc": "Irish female - gentle"},
]

# Emotion -> Kokoro speed. Kokoro has no inline emotion tags, but per-utterance
# speed gives real (if subtle) mood: excited talks faster, sad slower.
EMOTION_SPEED = {
    "excited": 1.18, "happy": 1.06, "neutral": 1.0,
    "calm": 0.94, "sad": 0.84, "angry": 1.12,
}

class TTSHandler:
    """Kokoro TTS - ONNX (preferred, ~0.3 GB, no torch) with torch fallback."""

    def __init__(self):
        self.ready = False
        self.pipeline = None       # kokoro-onnx instance
        self.torch_pipeline = None  # torch Kokoro fallback
        self.onnx = False
        # Task 8.3 review fix: the startup warm runs in an executor and is NOT
        # awaited, and run_chat independently calls ensure()/synthesize(). The
        # kokoro-onnx pipeline is not documented thread-safe, so the warm and a
        # fast first turn must never touch it at once. `_warm_event` is set once
        # the warm (if any) has finished; `_lock` serialises every pipeline call.
        # Startup stays non-blocking: `_warm_event` is pre-set, so a turn that
        # arrives before warmup() is only ever gated on the warm it actually
        # started (see warm()), never on a warm that will never come.
        self._warm_event = threading.Event()
        self._warm_event.set()
        self._lock = threading.Lock()

    def ensure(self):
        if self.ready:
            return
        with self._lock:
            self._ensure_locked()

    def _ensure_locked(self):
        if self.ready:
            return
        # Preferred: ONNX Runtime - ~0.3 GB vs ~2 GB for torch, no PyTorch needed
        try:
            from kokoro_onnx import Kokoro
            choice = str(CFG.get("tts_model", "fp16")).lower()
            files = {
                "int8": "kokoro-v1.0.int8.onnx",
                "fp16": "kokoro-v1.0.fp16.onnx",
                "fp32": "kokoro-v1.0.onnx",
            }
            filename = files.get(choice, files["fp16"])
            onnx_model = str(MODELS_DIR / "kokoro-onnx" / filename)
            onnx_voices = str(MODELS_DIR / "kokoro-onnx" / "voices-v1.0.bin")
            if not os.path.exists(onnx_model) and choice != "fp16":
                log(f"TTS model '{choice}' not found at {onnx_model}; using fp16")
                choice = "fp16"
                onnx_model = str(MODELS_DIR / "kokoro-onnx" / files["fp16"])
            if os.path.exists(onnx_model) and os.path.exists(onnx_voices):
                self.pipeline = Kokoro(onnx_model, onnx_voices)
                self.onnx = True
                self.ready = True
                log(f"Kokoro TTS loaded (ONNX {choice}, {os.path.getsize(onnx_model)//(1024*1024)} MB)")
                return
        except Exception as e:
            log(f"Kokoro ONNX load failed ({e}) - trying torch fallback")
        try:
            from kokoro import KPipeline
            self.torch_pipeline = KPipeline(lang_code='a')
            self.ready = True
            log("Kokoro-82M TTS loaded (torch fallback)")
        except Exception as e:
            log(f"TTS load error: {e}")
            self.ready = False

    def reload(self):
        """Drop the loaded model and re-read tts_model (used after a settings change)."""
        with self._lock:
            self.pipeline = None
            self.torch_pipeline = None
            self.onnx = False
            self.ready = False
        self.warm()

    def warm(self):
        """Load the model and synthesize one throwaway phrase, once, at startup.

        Holds ``_lock`` for the whole warm, so a turn that reaches synthesize()
        while the warm is in flight can only proceed once the warm has released
        it - the non-thread-safe ONNX pipeline is never entered by two callers at
        once. The event is set in ``finally`` so waiters in synthesize() never
        block beyond the warm, and it is pre-set in __init__ so a turn arriving
        when no warm was started is never gated. Never caches or plays the output.
        """
        self._warm_event.clear()
        try:
            with self._lock:
                self._ensure_locked()
                log("TTS warmup complete" if self.ready else "TTS warmup failed")
                if self.ready:
                    t0 = time.time()
                    wav = self._synth_locked("Hi.", CFG["tts_voice"], CFG["tts_speed"])
                    log(f"Kokoro warm synth {len(wav) if wav else 0} bytes "
                        f"in {(time.time() - t0) * 1000:.0f}ms")
        except Exception as e:
            log(f"Kokoro warm synth failed: {e}")
        finally:
            self._warm_event.set()

    def synthesize(self, text: str, voice: str = None, speed: float = None) -> bytes:
        speed = speed if speed else CFG["tts_speed"]
        if str(CFG.get("tts_engine", "kokoro")).lower() == "edge":
            try:
                return self._edge_synth(text, speed)
            except Exception as e:
                log(f"Edge TTS failed ({e}); falling back to Kokoro")
        # Task 8.3 review fix: if a startup warm is in flight, wait for it so we
        # never run two syntheses on the ONNX pipeline concurrently. `_warm_event`
        # is pre-set, so this only waits on a warm that was actually started.
        if not self._warm_event.is_set():
            log("TTS synth waiting for in-flight Kokoro warm")
        self._warm_event.wait()
        self.ensure()
        if not self.ready:
            return self._silent_wav(0.5)
        voice = voice or CFG["tts_voice"]
        with self._lock:
            return self._synth_locked(text, voice, speed)

    def _edge_synth(self, text: str, speed: float) -> bytes:
        """Microsoft Edge TTS (free online voices). Returns PCM_16 WAV bytes."""
        import asyncio
        import io as _io
        import soundfile as _sf
        import edge_tts
        rate = max(-40, min(40, int(round((speed - 1.0) * 100))))
        voice = str(CFG.get("edge_voice", "en-US-AvaNeural"))

        async def _run():
            comm = edge_tts.Communicate(text, voice=voice, rate=f"{rate:+d}%")
            data = bytearray()
            async for chunk in comm.stream():
                if chunk.get("type") == "audio":
                    data += chunk["data"]
            return bytes(data)

        t0 = time.time()
        mp3 = asyncio.run(asyncio.wait_for(_run(), timeout=25))
        if not mp3:
            raise RuntimeError("empty Edge audio")
        samples, sr = _sf.read(_io.BytesIO(mp3), dtype="float32")
        buf = _io.BytesIO()
        _sf.write(buf, samples, sr, format="WAV", subtype="PCM_16")
        log(f"Edge TTS {voice} {len(samples)/sr:.1f}s in {(time.time()-t0)*1000:.0f}ms")
        return buf.getvalue()

    def _synth_locked(self, text: str, voice: str, speed: float) -> bytes:
        try:
            if self.onnx:
                samples, sr = self.pipeline.create(text, voice=voice, speed=speed)
                pcm = (np.clip(samples, -1.0, 1.0) * 32767).astype(np.int16)
                buffer = io.BytesIO()
                sf.write(buffer, pcm, sr, format='WAV', subtype='PCM_16')
                return buffer.getvalue()
            # torch fallback: reuse the already-loaded pipeline
            audio_segments = []
            for gs, ps, audio in self.torch_pipeline(
                text,
                voice=voice,
                speed=speed,
                split_pattern=r'\n+',
            ):
                audio_segments.append(audio)
            if not audio_segments:
                return self._silent_wav(0.5)
            full_audio = np.concatenate(audio_segments)
            full_audio = np.clip(full_audio, -1.0, 1.0)
            pcm = (full_audio * 32767).astype(np.int16)
            buffer = io.BytesIO()
            sf.write(buffer, pcm, 24000, format='WAV', subtype='PCM_16')
            return buffer.getvalue()
        except Exception as e:
            log(f"TTS synthesis error: {e}")
            return self._silent_wav(0.5)

    def _silent_wav(self, seconds: float) -> bytes:
        sample_rate = 24000
        num_samples = int(sample_rate * seconds)
        data = b"\x00" * (num_samples * 2)
        header = struct.pack("<4sI4s4sIHHIIHH4sI",
            b"RIFF", 36 + len(data), b"WAVE", b"fmt ", 16, 1, 1,
            sample_rate, sample_rate * 2, 2, 16, b"data", len(data))
        return header + data

TTS = TTSHandler()

# Background-TTS queue: one atomically-written WAV per sentence. The voice helper
# plays files in name order as they appear, so the first sentence starts speaking
# while the rest of the reply is still being synthesized.
LAYA_SAY_DIR = BASE_DIR.parent / "logs" / "laya-say"


def _tts_chunks(text: str, max_chars: int = 140):
    """Sentences, then comma/semicolon clauses, bounded by max_chars.

    A comma-separated weapon list used to synthesize as one 16-second clip;
    splitting clauses lets playback start after the first short chunk.
    """
    sentences, rest = _split_sentences(text or "")
    if rest and rest.strip():
        sentences.append(rest.strip())
    chunks = []
    for sentence in sentences:
        pieces = re.split(r"(?<=[,;:])\s+", sentence)
        buf = ""
        for piece in pieces:
            piece = piece.strip()
            if not piece:
                continue
            if len(piece) > max_chars:
                if buf:
                    chunks.append(buf); buf = ""
                words = piece.split()
                line = ""
                for w in words:
                    if len(line) + len(w) + 1 > max_chars:
                        chunks.append(line); line = w
                    else:
                        line = (line + " " + w).strip()
                if line: buf = line
            elif len(buf) + len(piece) + 1 <= max_chars:
                buf = (buf + " " + piece).strip()
            else:
                if buf: chunks.append(buf)
                buf = piece
        if buf: chunks.append(buf)
    return [c for c in chunks if _is_speakable(c)]


async def _background_tts(text: str, emotion: str = ""):
    """Synthesize typed-chat replies sentence by sentence into logs/laya-say/.

    Sentence-level files let the voice helper begin playback after the first
    sentence instead of waiting for the whole reply. Kokoro itself is serialized
    by TTSHandler._lock, so sequential synthesis is the correct order.
    """
    if not CFG.get("tts_enabled", True):
        return
    t0 = time.time()
    try:
        import asyncio as _asyncio
        speed = EMOTION_SPEED.get(emotion, CFG["tts_speed"])
        sentences = _tts_chunks(text or "", 140)
        if not sentences:
            sentences = [text or ""]
        turn = str(int(time.time() * 1000))
        LAYA_SAY_DIR.mkdir(parents=True, exist_ok=True)
        written = 0
        for idx, sentence in enumerate(sentences):
            if not _is_speakable(sentence):
                continue
            wav = await _asyncio.get_event_loop().run_in_executor(
                None, lambda sent=sentence: TTS.synthesize(
                    sent, voice=CFG["tts_voice"], speed=speed))
            if not wav or len(wav) <= 1000:
                continue
            out = LAYA_SAY_DIR / f"{turn}-{idx:03d}.wav"
            tmp = out.with_suffix(".tmp.wav")
            tmp.write_bytes(wav)
            os.replace(tmp, out)  # atomic: the poller never sees a partial file
            written += 1
            if written == 1:
                log(f"Background TTS first sentence in {(time.time()-t0)*1000:.0f}ms "
                    f"({len(wav)} bytes, {len(sentences)} sentence(s))")
        if written:
            log(f"Background TTS complete: {written} sentence(s) in "
                f"{(time.time()-t0)*1000:.0f}ms")
    except Exception as e:
        log(f"Background TTS failed: {e}")

# ---------------------------------------------------------------------------
# Request / Response models
# ---------------------------------------------------------------------------

class ChatRequest(BaseModel):
    player: str
    message: str
    state: dict
    with_audio: bool = False  # Hytale plugin only uses say/action - skip TTS for speed


class ChatResponse(BaseModel):
    say: str
    action: str
    target_x: Optional[float] = None
    target_z: Optional[float] = None
    # Task schema (Task 3): the plugin routes {"task": ...} into its task branch.
    task: Optional[str] = None
    target_hint: Optional[str] = None
    # Cycle 5: 0 = all matching blocks in range, N = exactly N blocks.
    count: Optional[int] = None
    # Cycle 10c: "tree" = finish the anchored tree, "area" = every match within
    # the scan radius around the player (the plugin honours this for wood tasks).
    scope: Optional[str] = None
    # Requested area-clear radius in blocks (0 = plugin default).
    radius: Optional[int] = None
    audio_b64: Optional[str] = None


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@app.get("/health")
async def health():
    # Task 2: resolve the decision engine lazily and truthfully. ensure() does a
    # bounded (2 s) GET on the laya.cpp server; run it off the event loop so a
    # dead server cannot stall /health.
    # Task 4: with decision_engine == "rule-based" no probe happens at all.
    #
    # Fix (Important review): /health must NEVER block on ensure() while the
    # Task 3 startup spawn is still warming. _resolve() waits on
    # _LAYA_START_DONE for LAYA_START_BUDGET_S + 3.0 (~23 s) and the 2 s probe
    # on top, so an /health issued during warm-up used to stall for ~23 s -
    # turning the status probe (and START-LAYA.bat's 2 s-timeout poll loop) into
    # a boot-synchronized call. Report the truthful current value instead:
    # engine_name is already the resolved value ("rule-based" when the config
    # off-switch pinned it, "laya.cpp"/"rule-based" once ensure() ran, else
    # None while the server is still starting). Resolving stays the first
    # /chat's job, exactly as before; once warm, /health reports the real name.
    if DECISION.ready:
        engine_name = DECISION.engine_name
    elif _LAYA_START_DONE.is_set():
        # Warm-up finished (or was never needed) -> resolving is now cheap
        # (one bounded GET, no wait), so the first /health can still report the
        # settled value without the long block.
        await asyncio.get_event_loop().run_in_executor(None, DECISION.ensure)
        engine_name = DECISION.engine_name
    else:
        # Still warming: do not block. Report the truthful transient value.
        engine_name = DECISION.engine_name or "starting"
    return {
        "ok": True,
        "llm_ready": LLM.ready,
        "llm_providers": [p["name"] for p in LLM.providers],
        "decision_engine": engine_name,
        "tts_ready": TTS.ready,
        "tts_enabled": bool(CFG.get("tts_enabled", True)),
    }


@app.post("/chat")
async def chat(request: Request):
    raw_body = await request.body()
    log(f"POST /chat received, raw_body={raw_body[:400]}")
    if not raw_body:
        return ChatResponse(say="(Laya's bridge crashed - empty request body)", action="follow")
    try:
        payload = json.loads(raw_body.decode("utf-8"))
    except Exception as e:
        log(f"JSON parse failed: {e} - body={raw_body[:400]!r}")
        return ChatResponse(say="(Laya's bridge got malformed JSON)", action="follow")
    req = ChatRequest(**payload)
    # Task 8: this route is the plugin's typed-chat path -> a real user turn.
    return await run_chat(req.player, req.message, req.state, req.with_audio,
                          user_text_turn=True)


_CONTROL_KEYS = ("say", "emotion", "action", "task", "target_hint", "count",
                 "target_x", "target_z", "remember")
_CONTROL_PREFIXES = tuple(k + ":" for k in _CONTROL_KEYS)


def clean_speech(raw: str) -> tuple[str, str, str]:
    """Split a model reply into (say, action, emotion) without leaking control lines.

    Fix cycle 1: the old fallback spoke the whole raw reply when the "say:" line was
    empty, so the plugin said "emotion: neutral / action: follow" out loud.

    Fix cycle 4b: a `say:` body used to swallow every following line, so the normal
    `task:`/`target_hint:` pair after it was spoken aloud ("Understood ... task: mine
    target_hint: copper"). EVERY control key now terminates the speech block, not just
    action/emotion.
    """
    say_parts: list[str] = []
    action = ""
    emotion = ""
    in_say = False
    for line in (raw or "").splitlines():
        s = line.strip()
        key, sep, rest = s.partition(":")
        k = key.strip().lower() if sep else ""
        if k in _CONTROL_KEYS:
            rest = rest.strip()
            if k == "say":
                in_say = True
                if rest:
                    say_parts.append(rest)
            else:
                in_say = False
                if k == "emotion":
                    emotion = rest.lower()
                elif k == "action":
                    action = rest.lower()
            continue
        if in_say and s:
            say_parts.append(s)
    say = " ".join(say_parts).strip()
    if not say:
        plain = [
            s for s in (ln.strip() for ln in (raw or "").splitlines())
            if s and not s.lower().startswith(_CONTROL_PREFIXES)
        ]
        say = " ".join(plain).strip()
    return say, action, emotion


async def run_chat(player: str, message: str, state: dict, with_audio: bool,
                   user_text_turn: bool = False) -> ChatResponse:
    """Core chat pipeline - used by both the HTTP endpoint and the in-process voice helper."""
    start = time.time()
    state_json = json.dumps(state, separators=(",", ":"))
    # Combat Task 1: a pure deterministic command ("stop", "follow me", "stay
    # here") is decided from the raw text - it needs neither the ~2.5 s vision
    # capture nor the ~3 s LLM round-trip, both of which the guards below would
    # only overwrite. `fast` is None for everything else, so the normal path is
    # byte-identical for non-commands.
    fast = fast_command(message)
    # Task 8 (vision): only user-originated turns may look at the player's screen
    # (typed chat via POST /chat + the voice loop), and only when the message
    # actually asks her to look ("look at this", "what's going on", ...). This
    # keeps the normal chat path free of the ~2.5 s capture + describe cost.
    look = fast is None and user_text_turn and wants_vision(message)
    # Task 0: baseline turn timing (measurement only)
    t0 = time.time()
    vision_desc = await capture_and_describe(look, message)
    t_vision = time.time()
    # Persistent memory + retrieved game knowledge go into every prompt
    parts = []
    mem = memory_block()
    if mem:
        parts.append(mem)
    knowledge = retrieve_knowledge(message)
    if knowledge:
        parts.append("Game knowledge (from Hytale's own data):\n" + "\n".join(f"- {k}" for k in knowledge))
    inv = ""
    if asks_about_gear(message):
        inv = inventory_block()
    if inv:
        parts.append(inv)
    laya_state = (state or {}).get("laya") or {}
    appearance = ""
    if isinstance(laya_state, dict):
        appearance = str(laya_state.get("appearance") or "").strip()
    appearance_question = bool(re.search(
        r"\b(hairstyle|haircut|hair|appearance|outfit|clothes|wearing|dress|look)\b",
        (message or "").lower()))
    if asks_about_gear(message) or appearance_question:
        gear = gear_block(state, include_appearance=appearance_question)
        if gear:
            parts.append(gear)
    if appearance_question and appearance:
        parts.append(
            "The player is asking about Laya's appearance. The authoritative "
            "appearance above is the answer: reply directly and in character about "
            "that hair/outfit. Do not say you cannot see, do not talk about trusting "
            "a description, and do not give a generic companion line."
        )
    parts.append(
        "Use the live equipment/appearance line above for any question about "
        "what Laya is holding, wearing, or looks like; do not deny or contradict "
        "it. Do not mention Laya's held item, armor, or appearance unless the "
        "player asks about it or it is directly relevant to their instruction. "
        "Answer the player's current message first; do not drag in earlier "
        "topics or gear from the previous conversation unless they are relevant "
        "to what the player just said. If the player says they see something, "
        "treat that observation as true and respond to it naturally even when "
        "you have no vision line."
    )
    parts.append(f"Game state: {state_json}\n{player or 'Player'} says: {message}\nLaya:")
    prompt = "\n\n".join(parts)

    # Task 8 (vision): the system line is appended ONLY when a description came
    # back. description None -> no line, so a capture/vision failure can never
    # make Laya claim she "saw" something (the persona bans that).
    vision_system = ""
    if vision_desc:
        vision_system = (
            f'You can see the player\'s screen right now. Visible: "{vision_desc}". '
            f'Ground every claim about the scene in that description only: name what it '
            f'names, and if something is not in it, say you cannot make it out rather '
            f'than guessing. Lead with what is in the center of the view.'
        )

    # Task 7: stream the LLM reply when the flag is on. Streaming is armed for
    # every turn so the `Stream:` measurement and the metadata-safety check run
    # on typed turns too. Audio is ALWAYS synthesized as one full clip (see the
    # TTS block below): capture.py can only play a single full clip, so
    # per-sentence synthesis would add cost with no gain. All streaming turn
    # state lives in the `_on_sentence` closure — no module-global mutable state.
    t_stream_first = [None]
    stream_sentences: list[str] = []
    streaming = bool(CFG.get("llm_stream"))

    async def _on_sentence(sentence: str) -> None:
        # Last-line defence: never hand anything control-shaped to TTS. The
        # stream already filters metadata lines, but a callback must be safe
        # even if a future caller misuses it.
        if not CFG.get("tts_enabled", True):
            return
        if not _is_speakable(sentence) or _is_control_prefix(sentence):
            return
        stream_sentences.append(sentence)
        if t_stream_first[0] is None:
            t_stream_first[0] = time.time()
        # No per-sentence synthesis: capture.py plays one full clip, so a
        # per-sentence path would only add cost (see MODLOG). _StreamTts remains
        # as groundwork for a future capture.py partial-audio channel.

    raw = "" if fast else await LLM.generate(
        prompt,
        system_extra=vision_system,
        on_sentence=_on_sentence if streaming else None,
    )
    t_llm = time.time()

    # Requirement 2 (metadata safety): the union of streamed sentences must be
    # the spoken line, not a control line. Log lengths + a boolean per turn.
    if streaming and stream_sentences:
        _verify_stream_text(stream_sentences, raw)

    if fast:
        # Combat Task 1: the tuple is authoritative - no model line to parse.
        action, say_line = fast
        emotion = ""
        log(f"Fast command: {action}")
    else:
        say_line, action, emotion = clean_speech(raw)
    action = action or "follow"
    tx = None
    tz = None
    remember_line = ""
    task = ""
    target_hint = ""
    model_count = None

    for line in raw.split("\n"):
        line = line.strip()
        if line.lower().startswith("remember:"):
            remember_line = line[9:].strip()
        elif line.lower().startswith("task:"):
            task = line[5:].strip().lower()
        elif line.lower().startswith("target_hint:"):
            target_hint = line[12:].strip().lower()
        elif line.lower().startswith("count:"):
            try:
                model_count = int(line[6:].strip())
            except ValueError:
                pass
        elif line.lower().startswith("target_x:"):
            try:
                tx = float(line[9:].strip())
            except ValueError:
                pass
        elif line.lower().startswith("target_z:"):
            try:
                tz = float(line[9:].strip())
            except ValueError:
                pass

    # Cycle 5: deterministic intent leg. A cancel cue always wins (and must
    # never be re-read as a new task); otherwise a strong verb + hint word
    # forces the task even when the model refuses from stale memory.
    # Combat Task 1: a fast command already decided the action - skip the whole
    # leg (stop-task ordering for non-fast messages is unchanged below).
    if not fast and wants_stop_task(message):
        log("Guard: stop-task cue -> action=stop_task")
        action = "stop_task"
        task = ""
        target_hint = ""
    else:
        det_task, det_hint = detect_task(message)
        if det_task:
            # The raw text is authoritative: a stale-memory refusal or a
            # wrong verb (gather for ore) can't suppress the real request.
            if task != det_task or target_hint != det_hint:
                log(f"Guard: deterministic task overrides model -> {det_task} hint={det_hint}")
            task, target_hint = det_task, det_hint or ""
        elif det_task and not target_hint:
            target_hint = det_hint or ""
    if (task in ("mine", "gather") and target_hint
            and target_hint.lower() in WOOD_SPECIES
            and re.search(r"\b(trees?|wood|logs?|chop|cut|fell)\b", (message or "").lower())):
        target_hint = "wood"
    if task and not det_task and is_creature_attack(message):
        log(f"Guard: creature attack clears model task '{task}'")
        task = ""
        target_hint = ""
    count = derive_task_count(message, task, model_count, target_hint)
    scope = derive_task_scope(message, task, target_hint, count)
    radius = derive_task_radius(message, task, target_hint, count)
    if task in ("mine", "gather"):
        log(f"Task intent: {task} hint={target_hint or '?'} count={count if count else 'all'}"
            f" scope={scope or '-'} radius={radius or 'default'}")
        # Cycle 11b: a forced task always speaks its own acknowledgement. The
        # model line was written before the task existed and can echo a stale
        # failure from memory; this line names what she is about to do.
        ack = task_ack(task, target_hint, scope, count)
        if (say_line or "").strip() != ack:
            log(f"Guard: task ack replaces model line -> {ack}")
        say_line = ack

    # Cycle 12: explicit stance commands are deterministic. The model used to
    # answer "follow me" with stay (or stay put after "follow me"); the raw
    # text decides, and the spoken line confirms it. A fast command already
    # holds the same deterministic result, so only non-fast messages re-derive.
    if not fast and action != "stop_task":
        guarded_action, guarded_say = apply_stance_guard(message, action, say_line)
        if guarded_action != action:
            log(f"Guard: stance cue -> action={guarded_action} (kept model line)")
        action, say_line = guarded_action, guarded_say

    # Fix cycle 4b: `stay` only on an explicit cue. The follow ring is gated on
    # taskLive, so following through a task is safe and - if the task is refused -
    # she keeps walking with the player instead of standing where she stopped.
    if action == "stay" and not wants_stay(message):
        log("Guard: unprompted action=stay -> follow")
        action = "follow"

    # Name-aware attack: let the plugin match a named creature ("kill those pigs")
    # even when it is a protected passive and the crosshair is not on it.
    if action == "attack" and not task:
        target_hint = (message or "").strip()[:120]

    if not say_line:
        say_line = "I'm here."

    # Persistent memory: store notable facts + conversation history
    if remember_line and len(remember_line) > 4:
        existing = [f.lower() for f in MEMORY.get("facts", [])]
        if remember_line.lower() not in existing:
            MEMORY.setdefault("facts", []).append(remember_line)
            if len(MEMORY["facts"]) > 40:
                MEMORY["facts"] = MEMORY["facts"][-40:]
            log(f"Remembered: {remember_line}")
    MEMORY.setdefault("chat", []).append({"p": message, "l": say_line})
    if len(MEMORY["chat"]) > 16:
        MEMORY["chat"] = MEMORY["chat"][-16:]
    save_memory()

    options = ["follow", "attack", "defend", "stay", "move_to"]
    # Task 9: "gift_hand" is a real action verb the persona can emit (Task 7's
    # gift), but it is NOT part of the decision engine's option set. Without this
    # guard the persona's `action: gift_hand` would be overwritten by
    # DECISION.decide(...) and never reach the plugin (whose applyCommand does
    # handle gift_hand). Pass it straight through; keep `options` unchanged so the
    # decision engine can never spontaneously gift.
    if action not in options and action not in ("gift_hand", "stop_task"):
        # Combat Task 1: a fast command's action is final - never hand it to the
        # (possibly remote) decision engine, which would add latency for nothing.
        if not fast:
            action = DECISION.decide(state_json, options)

    t_decision = time.time()

    audio_b64 = None
    failure_reply = is_llm_failure(say_line)
    if failure_reply:
        # All providers down: this is a status notice, not speech. Keep it in
        # the chat window but never synthesize it (voice or background file).
        log("TTS skipped: LLM failure reply (no provider answered)")
    elif not CFG.get("tts_enabled", True):
        # Task 7: master switch off - no voice audio, no background file.
        log("TTS skipped: tts_enabled is off")
        audio_b64 = None
    elif with_audio:
        try:
            import asyncio as _asyncio
            speed = EMOTION_SPEED.get(emotion, CFG["tts_speed"])
            if emotion:
                log(f"Emotion: {emotion} -> speed {speed}")
            # Task 7 fix: ALWAYS synthesize the whole spoken line as one clip.
            # capture.py plays a single full clip via blocking sd.play/sd.wait,
            # so per-sentence synthesis + stitching only added ~1.7 s with no
            # early playback. This keeps the voice audio cost identical to the
            # flag-off path while the reply is still streamed for lower latency
            # to the caller (say/action returned as soon as the text is done).
            wav_bytes = await _asyncio.get_event_loop().run_in_executor(
                None, lambda: TTS.synthesize(say_line, voice=CFG["tts_voice"], speed=speed))
            audio_b64 = base64.b64encode(wav_bytes).decode("ascii") if wav_bytes else None
        except Exception as e:
            log(f"TTS error: {e}")
    else:
        # Typed-chat path: reply fast, synthesize voice in the background and
        # write it to logs/laya-say.wav - the in-process poller plays new files.
        # Task 7: this path is NEVER streamed; behavior is byte-identical.
        import asyncio as _asyncio
        _asyncio.get_event_loop().create_task(_background_tts(say_line, emotion))

    elapsed = (time.time() - start) * 1000
    log(f"Responded in {elapsed:.0f}ms")

    # Task 7 measurement: one line per streamed turn. first_sentence is the
    # wall time from turn start to the first sentence handed to TTS; total is
    # the whole turn. Kept alongside (not replacing) the Turn timing line.
    t_end = time.time()
    if streaming and t_stream_first[0] is not None:
        log(f"Stream: first_sentence {(t_stream_first[0] - t0) * 1000:.0f}ms, "
            f"total {(t_end - t0) * 1000:.0f}ms, chars {len(raw)}")

    # Task 0: baseline turn timing (one line per turn)
    log(f"Turn timing: total {(t_end - t0) * 1000:.0f}ms "
        f"(vision {(t_vision - t0) * 1000:.0f}, llm {(t_llm - t_vision) * 1000:.0f}, "
        f"decision {(t_decision - t_llm) * 1000:.0f}, tts {(t_end - t_decision) * 1000:.0f})")

    return ChatResponse(
        say=say_line,
        action=action,
        target_x=tx,
        target_z=tz,
        task=task if task and task != "none" else None,
        target_hint=target_hint if target_hint and target_hint != "none" else None,
        count=count if task and task != "none" else None,
        scope=scope if task and task != "none" else None,
        radius=radius if task and task != "none" and radius else None,
        audio_b64=audio_b64,
    )


# ---------------------------------------------------------------------------
# In-game events from the plugin (Task 3): identify / mine / gather outcomes
# ---------------------------------------------------------------------------

PLUGIN_COMMAND_URL = CFG.get("plugin_bridge_url", "http://127.0.0.1:8787/command")


async def _react_to_event(event: str, detail: str):
    """One LLM call reacting to an observed event, then push {"say": ...} to the plugin.

    Task 8: this is a NON-user turn (the plugin observed something, the player did
    not speak). It calls LLM.generate directly and never goes through run_chat, so
    it performs ZERO vision capture by construction (run_chat's user_text_turn
    defaults to False for exactly this kind of caller).
    """
    try:
        # Cycle 10: the old "Laya event: ..." line was echoed verbatim by the model
        # (and then spoken by TTS). Use the same Player-says shape as normal chat and
        # reject any reply that repeats the instruction.
        prompt = (
            f"Game state: an event just happened ({event}): {detail}\n"
            "Player says: (nobody spoke - this is your own observation)\n"
            "Laya:"
        )
        inv = inventory_block()
        system_extra = ("You just observed this event; reply as Laya, 2 sentences at most. "
                        "Do not repeat or quote these instructions.")
        if inv:
            system_extra += "\n\n" + inv
        raw = await LLM.generate(prompt, system_extra=system_extra)
        say, _action, emotion = clean_speech(raw)
        lower = (say or "").lower()
        if (not say or "laya event" in lower or "event kind" in lower
                or "nobody spoke" in lower or "game state:" in lower):
            log(f"Event reaction echoed the prompt - using a plain line instead: {(say or '')[:120]}")
            say = {
                "task_failed": "I couldn't reach that one - want me to try another spot?",
                "task_done": "Done with that - what next?",
            }.get(event, "Hm, that didn't work.")
        if say.lower().startswith("laya:"):
            say = say[5:].strip()
        if not say:
            log("Event reaction produced nothing — not sending to the plugin")
            return
        # Cycle 9: event reactions never reached the speakers because this path
        # skipped the background TTS the typed-reply path uses. The plugin now
        # delivers the chat line; this writes the same voice file for the player.
        # LLM failure notices are text-only status lines, never speech.
        if not is_llm_failure(say) and CFG.get("tts_enabled", True):
            import asyncio as _asyncio
            _asyncio.get_event_loop().create_task(_background_tts(say, emotion))
        else:
            log("TTS skipped: LLM failure reply (event reaction)")
        MEMORY.setdefault("chat", []).append({"p": f"(observed: {detail})", "l": say})
        save_memory()
        async with httpx.AsyncClient(timeout=5.0) as client:
            resp = await client.post(PLUGIN_COMMAND_URL, json={"say": say})
        log(f"Event reaction -> plugin /command ({resp.status_code}): {say}")
    except Exception as e:
        log(f"Event reaction failed: {e}")


@app.post("/npc_event")
async def npc_event(request: Request):
    """Observation posted by the plugin: {"event": "identified|task_failed|task_done",
    "detail": "..."}.

    The detail is appended to Laya's memory (so it lands in the next LLM context as
    "Laya event: <detail>") and one LLM call reacts to it right away; the reply is
    posted back to the plugin's existing /command surface as {"say": ...}.
    """
    import asyncio

    raw_body = await request.body()
    log(f"POST /npc_event received, raw_body={raw_body[:400]}")
    try:
        payload = json.loads(raw_body.decode("utf-8") or "{}")
    except Exception as e:
        log(f"npc_event JSON parse failed: {e}")
        return JSONResponse(status_code=400, content={"ok": False, "error": "bad json"})
    event = str(payload.get("event", "")).strip()
    detail = str(payload.get("detail", "")).strip()
    if not event:
        return JSONResponse(status_code=422, content={"ok": False, "error": "event required"})

    MEMORY.setdefault("events", []).append({"event": event, "detail": detail})
    if len(MEMORY["events"]) > 20:
        MEMORY["events"] = MEMORY["events"][-20:]
    save_memory()
    log(f"Laya event: {detail}")
    # React immediately, but don't hold the plugin's POST open for the LLM call.
    asyncio.get_event_loop().create_task(_react_to_event(event, detail))
    return {"ok": True, "event": event, "detail": detail}


@app.post("/tts")
async def tts(text: str):
    wav_bytes = TTS.synthesize(text, voice=CFG["tts_voice"])
    return Response(content=wav_bytes, media_type="audio/wav")


# ---------------------------------------------------------------------------
# Settings web UI - http://127.0.0.1:8788/settings
# ---------------------------------------------------------------------------

@app.get("/api/config")
async def get_config():
    """Current config with API keys masked."""
    safe = dict(CFG)
    for k in ("groq_api_key", "google_api_key", "zai_api_key"):
        if safe.get(k):
            safe[k] = safe[k][:8] + "..." + safe[k][-4:]
    return safe


@app.get("/api/voices")
async def list_voices():
    """Kokoro v1.0 English voices for the settings dropdown."""
    return {"voices": KOKORO_VOICES, "edge_voices": EDGE_VOICES,
            "emotions": list(EMOTION_SPEED.keys())}


@app.get("/api/audio-devices")
async def audio_devices():
    """List input/output audio devices for the settings page."""
    import sounddevice as sd
    devices = sd.query_devices()
    default_in = sd.default.device[0]
    default_out = sd.default.device[1]
    ins = [{"index": i, "name": d["name"], "channels": d["max_input_channels"]}
           for i, d in enumerate(devices) if d["max_input_channels"] > 0]
    outs = [{"index": i, "name": d["name"]}
            for i, d in enumerate(devices) if d["max_output_channels"] > 0]
    return {"inputs": ins, "outputs": outs,
            "default_input": default_in, "default_output": default_out}


@app.post("/api/config")
async def save_config(request: Request):
    """Merge posted fields into bridge-config.json and hot-reload.
    Guards against the settings page sending back masked keys ('...') and
    stringified arrays - those would corrupt the config."""
    body = json.loads((await request.body()).decode("utf-8") or "{}")
    cfg_path = BASE_DIR.parent / "config" / "bridge-config.json"
    try:
        current = json.loads(cfg_path.read_text(encoding="utf-8"))
    except Exception:
        current = {}

    # Sanitize before merging
    cleaned = {}
    for k, v in body.items():
        # Never let a masked display value overwrite a real API key
        if k.endswith("_api_key") and isinstance(v, str) and "..." in v:
            log(f"  Ignoring masked {k} (display value, not a real key)")
            continue
        # groq_models / zai_models arrive as comma-strings from the HTML input
        if k in ("groq_models", "zai_models") and isinstance(v, str):
            v = [m.strip() for m in v.split(",") if m.strip()]
        cleaned[k] = v

    current.update(cleaned)
    cfg_path.write_text(json.dumps(current, indent=4), encoding="utf-8")
    # Hot-reload
    CFG.update(cleaned)
    if any(k in cleaned for k in ("groq_api_key", "google_api_key", "zai_api_key",
                                  "groq_models", "zai_models", "google_model")):
        LLM.__init__()  # rebuild provider list
        log("LLM providers rebuilt after config change")
    if "tts_model" in cleaned:
        threading.Thread(target=TTS.reload, daemon=True).start()
        log(f"TTS model change -> reloading {cleaned['tts_model']} in background")
    if "persona_prompt" in body:
        global PERSONA
        PERSONA = load_persona()
    log(f"Config saved: {list(cleaned.keys())}")
    return {"ok": True, "updated": list(cleaned.keys())}


@app.post("/api/tts-test")
async def tts_test(request: Request):
    """Synthesize a sample sentence with the configured voice."""
    body = json.loads((await request.body()).decode("utf-8") or "{}")
    text = body.get("text", "Hey! I'm Laya, your companion in Hytale.")
    if str(CFG.get("tts_engine", "kokoro")).lower() == "edge":
        voice = CFG.get("edge_voice", "en-US-AvaNeural")
    else:
        voice = body.get("voice", CFG["tts_voice"])
    import asyncio
    wav = await asyncio.get_event_loop().run_in_executor(
        None, lambda: TTS.synthesize(text, voice=voice))
    return Response(content=wav, media_type="audio/wav")


@app.post("/api/llm-test")
async def llm_test():
    """Quick LLM connectivity test."""
    import asyncio
    t0 = time.time()
    raw = await LLM.generate("Say exactly: Laya is online")
    return {"ok": "Laya is online" in raw or "online" in raw.lower(),
            "reply": raw[:200], "latency_ms": int((time.time() - t0) * 1000)}


@app.get("/", response_class=HTMLResponse)
async def home_page():
    """Main landing page: lists the settings page and the appearance editor."""
    return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Laya Companion</title>
<style>
  :root { color-scheme: dark; --bg:#14141f; --panel:#1d1d2e; --panel2:#24243a; --line:#34344f; --text:#e9e9f2; --muted:#9a9ab0; --accent:#7ec8e3; --accent2:#98d8c8; --danger:#e37e8a; }
  * { box-sizing: border-box; }
  body { margin:0; min-height:100vh; font-family:"Segoe UI",system-ui,sans-serif;
    background: radial-gradient(1100px 650px at 15% -10%, #23234a 0%, var(--bg) 55%);
    color:var(--text); display:flex; align-items:center; justify-content:center; }
  .wrap { width:100%; max-width:880px; padding:48px 22px; }
  .status { display:flex; align-items:center; gap:8px; color:var(--muted); font-size:13px; margin-bottom:22px; }
  .dot { width:8px; height:8px; border-radius:50%; background:#6b6b85; }
  .dot.on { background:var(--accent2); }
  .dot.off { background:var(--danger); }
  h1 { margin:0; font-size:42px; letter-spacing:.4px; }
  h1 span { color:var(--accent); }
  .sub { color:var(--muted); font-size:15px; margin:10px 0 0; max-width:620px; line-height:1.55; }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(280px,1fr)); gap:18px; margin-top:30px; }
  a.card { display:block; text-decoration:none; color:inherit; background:linear-gradient(180deg,var(--panel2),var(--panel));
    border:1px solid var(--line); border-radius:12px; padding:22px 22px 20px;
    transition:transform .15s ease, border-color .15s ease; }
  a.card:hover { transform:translateY(-3px); border-color:var(--accent); }
  .icon { font-size:32px; line-height:1; }
  .card h2 { margin:16px 0 6px; font-size:18px; color:var(--accent2); }
  .card p { margin:0; color:var(--muted); font-size:13.5px; line-height:1.5; }
  .go { display:inline-block; margin-top:16px; color:var(--accent); font-size:13px; font-weight:700; }
  .foot { margin-top:34px; color:#6f6f88; font-size:12px; line-height:1.6; }
  code { color:var(--accent2); }
</style>
</head>
<body>
<div class="wrap">
  <div class="status"><span class="dot" id="dot"></span><span id="stat">Checking bridge...</span></div>
  <h1>Laya <span>Companion</span></h1>
  <p class="sub">Your AI companion for Hytale. Pick a page below. Both pages talk to the same running bridge and plugin.</p>
  <div class="grid">
    <a class="card" href="/settings">
      <div class="icon">&#9881;</div>
      <h2>Settings</h2>
      <p>Push-to-talk and teleport keys, voice and audio devices, LLM models, API keys, and connection status.</p>
      <span class="go">Open settings &rarr;</span>
    </a>
    <a class="card" href="/appearance">
      <div class="icon">&#127912;</div>
      <h2>Laya Appearance Editor</h2>
      <p>Change her character-creator look - hair, face, outfit and accessories - and apply it live in game.</p>
      <span class="go">Open appearance editor &rarr;</span>
    </a>
  </div>
  <p class="foot">Bridge: <code>127.0.0.1:8788</code> &middot; Appearance saves to <code>config/laya-skin.json</code> &middot; The in-game plugin listens on <code>127.0.0.1:8787</code>.</p>
</div>
<script>
  const dot = document.getElementById('dot');
  const stat = document.getElementById('stat');
  fetch('/health').then(r => r.json()).then(d => {
    dot.classList.add(d.ok ? 'on' : 'off');
    stat.textContent = d.ok
      ? 'Bridge online' + (d.tts_ready ? ' - voice ready' : ' - voice loading')
      : 'Bridge responded, but is not healthy';
  }).catch(() => {
    dot.classList.add('off');
    stat.textContent = 'Bridge offline';
  });
</script>
</body>
</html>"""


@app.get("/appearance", response_class=HTMLResponse)
async def appearance_page():
    """Cycle 12: Laya's wardrobe. Live edits, saved to config/laya-skin.json."""
    path = BASE_DIR / "appearance.html"
    if not path.exists():
        return HTMLResponse("<h1>appearance.html is missing</h1>", status_code=500)
    return HTMLResponse(path.read_text(encoding="utf-8"))


@app.get("/api/cosmetics")
async def api_cosmetics():
    """Proxy the plugin's cosmetic registry so the page is same-origin."""
    try:
        async with httpx.AsyncClient(timeout=8) as client:
            r = await client.get("http://127.0.0.1:8787/cosmetics")
        try:
            data = r.json()
        except Exception:
            return JSONResponse(
                {"ok": False, "error": f"Bad plugin response ({r.status_code})"},
                status_code=502)
        return JSONResponse(data, status_code=200 if r.status_code == 200 else 502)
    except Exception as e:
        return JSONResponse(
            {"ok": False, "error": f"Laya's plugin isn't reachable: {e}"},
            status_code=503)


@app.post("/api/appearance")
async def api_appearance(request: Request):
    """Forward one full skin to the plugin's set_appearance action."""
    body = json.loads((await request.body()).decode("utf-8") or "{}")
    cmd = {"action": "set_appearance"}
    for field in SKIN_FIELDS:
        value = body.get(field)
        cmd[f"skin_{field}"] = value if isinstance(value, str) else ""
    try:
        async with httpx.AsyncClient(timeout=8) as client:
            r = await client.post(PLUGIN_COMMAND_URL, json=cmd)
        return {"ok": r.status_code == 200}
    except Exception as e:
        return JSONResponse({"ok": False, "error": str(e)}, status_code=503)


@app.get("/settings")
async def settings_page():
    html = """<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Laya Settings</title>
<style>
body{font-family:Segoe UI,sans-serif;background:#1a1a2e;color:#eee;max-width:720px;margin:24px auto;padding:0 16px}
h1{color:#7ec8e3}h2{color:#98d8c8;border-bottom:1px solid #333;padding-bottom:6px;margin-top:28px}
.card{background:#16213e;border-radius:8px;padding:16px;margin:12px 0}
label{display:block;margin:10px 0 4px;color:#bbb;font-size:14px}
input,select{width:100%;padding:8px;background:#0f3460;color:#fff;border:1px solid #333;border-radius:4px;font-size:14px}
button{background:#7ec8e3;color:#1a1a2e;border:none;padding:8px 16px;border-radius:4px;cursor:pointer;font-weight:bold;margin:8px 6px 0 0}
button:hover{background:#a8e0f0}
.status{font-size:13px;color:#888;margin:4px 0}
.ok{color:#7ec8e3}.bad{color:#e07a7a}
.row{display:flex;gap:12px}.row>div{flex:1}
#result{margin-top:10px;padding:10px;background:#0a0a1a;border-radius:4px;font-size:13px;white-space:pre-wrap;display:none}
a.home{display:inline-block;color:#98d8c8;text-decoration:none;font-size:13px;margin-bottom:8px}
a.home:hover{color:#7ec8e3}
</style></head><body>
<a class="home" href="/">&larr; Laya home</a>
<h1>&#128153; Laya Companion Settings</h1>
<div class="card"><h2 style="margin-top:0">Status</h2><div id="status">Loading...</div></div>

<div class="card"><h2 style="margin-top:0">Voice &amp; Chat</h2>
<label>Talk key (push-to-talk in voice helper)</label>
<div style="display:flex;gap:8px;align-items:center">
<input id="talk_key" readonly style="flex:1;background:#0a0a1a">
<button onclick="captureKey('talk_key','keybtn')" id="keybtn">&#127908; Click, then press any key...</button>
</div>
<div id="keyhint" class="status">Current key shown above. Click the button, press the key you want (e.g. Left Alt), then Save. Esc cancels.</div>
<label>Teleport Laya-to-me key (unstick her)</label>
<div style="display:flex;gap:8px;align-items:center">
<input id="tp_key" readonly style="flex:1;background:#0a0a1a">
<button onclick="captureKey('tp_key','tpkeybtn')" id="tpkeybtn">&#128204; Click, then press any key...</button>
</div>
<div class="row">
<div><label>Player name (in-game)</label><input id="player_name"></div>
</div>
<div class="row" style="align-items:flex-end">
<div><label>TTS engine</label>
<select id="tts_engine">
  <option value="kokoro">Kokoro (local)</option>
  <option value="edge">Edge TTS (online, free)</option>
</select></div>
<div id="edgeVoiceWrap"><label>Edge voice (online)</label>
<select id="edge_voice"></select></div>
</div>
<div class="row" style="align-items:flex-end">
<div id="kokoroVoiceWrap"><label>Kokoro voice (local)</label>
<select id="tts_voice"></select></div>
<div style="flex:0 0 150px"><label>TTS speed (0.5 - 2.0)</label><input id="tts_speed" type="number" step="0.1" min="0.5" max="2.0"></div>
<div style="flex:0 0 150px"><label>TTS model</label>
<select id="tts_model">
  <option value="fp16">fp16 (default)</option>
  <option value="int8">int8 (smaller/faster)</option>
  <option value="fp32">fp32 (largest)</option>
</select></div>
</div>
<div class="status">Changing the model reloads TTS in the background; wait for "TTS warmup complete" in the bridge log.</div>
<label style="display:flex;align-items:center;gap:8px;margin-top:14px;color:#eee">
<input id="tts_enabled" type="checkbox" style="width:auto;margin:0">
Speak replies (TTS)</label>
<div class="status">Off silences every automatic reply; the "Test voice" button still speaks.</div>
</div>

<div class="card"><h2 style="margin-top:0">Audio devices</h2>
<label>Microphone (input)</label><select id="input_device"></select>
<label>Speakers (output)</label><select id="output_device"></select>
</div>

<div class="card"><h2 style="margin-top:0">LLM (cloud brain)</h2>
<label>Groq models (comma-separated, tried in order)</label><input id="groq_models">
<label>Google model</label><input id="google_model">
<label>z.ai models (comma-separated, tried in order)</label><input id="zai_models" placeholder="glm-4.5-flash, glm-4.6">
<div class="row">
<div><label>Temperature (0-1)</label><input id="llm_temperature" type="number" step="0.1" min="0" max="1"></div>
<div><label>Max tokens</label><input id="llm_max_tokens" type="number" step="16" min="16" max="512"></div>
</div>
</div>

<div class="card"><h2 style="margin-top:0">API keys (masked)</h2>
<label>Groq key (paste new to replace)</label><input id="groq_api_key" placeholder="paste key">
<label>Google key (paste new to replace)</label><input id="google_api_key" placeholder="paste key">
<label>z.ai key (paste new to replace)</label><input id="zai_api_key" placeholder="z.ai API key...">
</div>

<button onclick="save()">&#128190; Save settings</button>
<button onclick="testLLM()">&#9889; Test LLM</button>
<button onclick="testTTS()">&#127908; Test voice</button>
<button onclick="restart()">&#8635; Restart helper</button>
<div id="result"></div>

<script>
const FIELDS = ['talk_key','tp_key','player_name','tts_engine','tts_voice','edge_voice','tts_speed','tts_model','groq_models','google_model',
  'llm_temperature','llm_max_tokens','groq_api_key','google_api_key','zai_api_key','zai_models'];
const NUM = new Set(['tts_speed','llm_temperature','llm_max_tokens']);
const ARR = new Set(['groq_models','zai_models']);

async function load(){
  const cfg = await (await fetch('/api/config')).json();
  // 1. Simple text/number inputs first (arrays arrive as lists -> comma string)
  for(const f of FIELDS){
    if(f==='tts_voice' || f==='edge_voice') continue; // pickers, handled below
    const el=document.getElementById(f);
    if(!el) continue;
    let v = cfg[f];
    if(ARR.has(f) && Array.isArray(v)) v = v.join(', ');
    if(v!=null && v!=='') el.value=v;
  }
  // 2. Voice select: fill options, then set the current value
  const vs = await (await fetch('/api/voices')).json();
  const vsel = document.getElementById('tts_voice');
  const cur = cfg['tts_voice'] || 'af_heart';
  vsel.innerHTML = vs.voices.map(v=>'<option value="'+v.id+'"'+(v.id===cur?' selected':'')+'>'+v.id+' - '+v.desc+'</option>').join('');
  const esel = document.getElementById('edge_voice');
  const ecur = cfg['edge_voice'] || 'en-US-AvaNeural';
  const ev = vs.edge_voices || [];
  esel.innerHTML = ev.map(v=>'<option value="'+v.id+'"'+(v.id===ecur?' selected':'')+'>'+v.id+' - '+v.desc+'</option>').join('');
  document.getElementById('tts_enabled').checked = cfg['tts_enabled'] !== false;
  // 3. Status + device dropdowns
  const s = await (await fetch('/health')).json();
  document.getElementById('status').innerHTML =
    'LLM: <span class="'+(s.llm_ready?'ok':'bad')+'">'+(s.llm_ready?s.llm_providers.join(', '):'OFFLINE')+'</span>'+
    ' &nbsp;|&nbsp; TTS: <span class="'+(s.tts_ready?'ok':'bad')+'">'+(s.tts_ready?'ready':'loading...')+'</span>'+
    ' &nbsp;|&nbsp; Voice: <span class="'+(s.tts_enabled?'ok':'bad')+'">'+(s.tts_enabled?'on':'off')+'</span>';
  const ad = await (await fetch('/api/audio-devices')).json();
  const fill=(id, arr, sel)=>{ const e=document.getElementById(id);
    e.innerHTML = arr.map(d=>'<option value="'+d.index+'"'+(d.index==sel?' selected':'')+'>#'+d.index+' '+d.name+'</option>').join('');
    e.insertAdjacentHTML('afterbegin','<option value="">-- system default --</option>'); };
  fill('input_device', ad.inputs, cfg['input_device']);
  fill('output_device', ad.outputs, cfg['output_device']);
  syncEngineUI();
}
async function save(){
  const body={};
  for(const f of FIELDS){ const el=document.getElementById(f); if(el && el.value!=='') body[f]=NUM.has(f)?Number(el.value):el.value; }
  body['tts_enabled'] = document.getElementById('tts_enabled').checked;
  for(const f of ['input_device','output_device']){ const el=document.getElementById(f); body[f]=el.value===''?null:Number(el.value); }
  const r = await (await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)})).json();
  show('Saved: '+r.updated.join(', ')+'. Voice helper picks up new settings on next run.');
}
async function testLLM(){ show('Testing LLM...'); const r=await (await fetch('/api/llm-test',{method:'POST'})).json();
  show((r.ok?'LLM OK':'LLM FAILED')+' ('+r.latency_ms+'ms)\\n'+r.reply); }
function syncEngineUI(){
  const edge = document.getElementById('tts_engine').value === 'edge';
  document.getElementById('kokoroVoiceWrap').style.display = edge ? 'none' : '';
  document.getElementById('edgeVoiceWrap').style.display = edge ? '' : 'none';
}
document.getElementById('tts_engine').addEventListener('change', syncEngineUI);

async function testTTS(){ await save(); show('Testing voice (check speakers)...');
  const voice = document.getElementById('tts_voice').value;
  const resp=await fetch('/api/tts-test',{method:'POST',headers:{'Content-Type':'application/json'},
    body:JSON.stringify({voice: voice})});
  const blob=await resp.blob(); const a=new Audio(URL.createObjectURL(blob)); a.play();
  show('Playing test voice with: '+(voice||'af_heart')); }
async function restart(){ show('Restart the voice helper from its terminal:\\npython D:\\\\Games\\\\Hytale\\\\LayaCompanion\\\\voice-helper\\\\capture.py'); }
function show(t){ const e=document.getElementById('result'); e.style.display='block'; e.textContent=t; }

// --- Click-to-choose talk key ---
let capturing = false;
let _capInput='talk_key', _capBtn='keybtn';
function captureKey(inputId='talk_key', btnId='keybtn'){ _capInput=inputId; _capBtn=btnId; startCapture(); }
function startCapture(){
  capturing = true;
  document.getElementById(_capBtn).textContent = 'Press any key now... (Esc cancels)';
  document.getElementById('keyhint').textContent = 'Waiting for key press...';
}
function onKeyCapture(e){
  if(!capturing) return;
  e.preventDefault();
  e.stopPropagation();
  if(e.code === 'Escape'){
    capturing = false;
    document.getElementById(_capBtn).innerHTML = '&#127908; Click, then press any key...';
    document.getElementById('keyhint').textContent = 'Cancelled. Current key unchanged.';
    return;
  }
  const kbName = codeToKeyboardName(e);
  document.getElementById(_capInput).value = kbName;
  capturing = false;
  document.getElementById(_capBtn).innerHTML = '&#127908; Click, then press any key...';
  document.getElementById('keyhint').textContent =
    'Captured: "' + (e.key === ' ' ? 'Space' : e.key) + '" -> ' + _capInput + ' = "' + kbName + '". Now click Save settings.';
}
function codeToKeyboardName(e){
  const code = e.code;
  if(code.startsWith('Key')) return code.slice(3).toLowerCase();
  if(code.startsWith('Digit')) return code.slice(5);
  if(/^F\d{1,2}$/.test(code)) return code.toLowerCase();
  const map = {
    'AltLeft':'left alt','AltRight':'right alt',
    'ShiftLeft':'left shift','ShiftRight':'right shift',
    'ControlLeft':'left ctrl','ControlRight':'right ctrl',
    'MetaLeft':'windows','MetaRight':'windows',
    'Space':'space','Escape':'esc','Tab':'tab','Enter':'enter',
    'Backspace':'backspace','CapsLock':'caps lock','Delete':'delete','Insert':'insert',
    'Home':'home','End':'end','PageUp':'page up','PageDown':'page down',
    'ArrowUp':'up','ArrowDown':'down','ArrowLeft':'left','ArrowRight':'right',
    'Minus':'-','Equal':'=','BracketLeft':'[','BracketRight':']',
    'Backslash':'\\\\','Semicolon':';','Quote':"'",'Comma':',',
    'Period':'.','Slash':'/','Backquote':'`',
    'NumpadAdd':'+','NumpadSubtract':'-','NumpadMultiply':'*','NumpadDivide':'/',
    'NumpadEnter':'enter','NumpadDecimal':'.',
    'Numpad0':'0','Numpad1':'1','Numpad2':'2','Numpad3':'3','Numpad4':'4',
    'Numpad5':'5','Numpad6':'6','Numpad7':'7','Numpad8':'8','Numpad9':'9',
  };
  if(map[code]) return map[code];
  return e.key.toLowerCase();
}
window.addEventListener('keydown', onKeyCapture);

load();
</script>
</body></html>"""
    return Response(content=html, media_type="text/html")


@app.on_event("startup")
async def warmup():
    """Load Kokoro TTS in the background so the FIRST reply isn't slow,
    and start the in-process voice helper (mic + STT + playback)."""
    import asyncio
    log("Appearance editor: http://127.0.0.1:8788/appearance")

    # Task 3: own the laya.cpp server. Best-effort and non-blocking; a failure
    # only warns once (inside _start_laya_server) and boot continues.
    # Task 4: honor decision_engine - when it is "rule-based", never spawn the
    # server at all (otherwise "rule-based" would still pay GPU/model cost).
    # The start event is still set so any waiter unblocks immediately.
    if _decision_engine_is_laya():
        asyncio.get_event_loop().run_in_executor(None, _start_laya_server)
    else:
        log("decision_engine=rule-based: laya.cpp server not started")
        _LAYA_START_DONE.set()

    def _warm():
        # Task 8.3: load the model and pay the first-synthesis cost once at
        # startup so the first real turn does not. TTS.warm() holds the TTS lock
        # for the whole warm, so a fast first turn cannot enter the ONNX pipeline
        # concurrently; it waits on TTS._warm_event instead. Still non-blocking:
        # this runs in an executor and uvicorn binds/answers first.
        # Failures are logged once and ignored (the turn path handles them too).
        # laya.cpp /health is already handled by Task 3's startup probe above.
        TTS.warm()

    asyncio.get_event_loop().run_in_executor(None, _warm)

    if CFG.get("voice_helper_enabled", True):
        try:
            import voice
            voice.log = log
            voice.start(CFG, run_chat, asyncio.get_event_loop())
        except Exception as e:
            log(f"Voice helper failed to start: {e}")


@app.on_event("shutdown")
async def shutdown():
    """Task 3.2: stop the laya.cpp server we started when the app shuts down.

    Registered both as a FastAPI shutdown event and (in __main__) as a uvicorn
    Server.shutdown override, so Ctrl+C / `taskkill` without /F both run it.
    """
    _stop_laya_server()


if __name__ == "__main__":
    import uvicorn
    _server = uvicorn.Server(uvicorn.Config(app, host="127.0.0.1", port=8788))

    # Task 3.2: uvicorn's Server.shutdown() is the single funnel for a graceful
    # stop (Ctrl+C, SIGTERM, kill-without-force). Doing it here - rather than
    # only in atexit - means the child tree dies even on the signal path, not
    # just on a normal interpreter exit. NOTE: uvicorn.run() takes an app, not
    # a Server, so the Server is driven with .run() directly.
    _uvicorn_shutdown = _server.shutdown

    async def _shutdown_and_stop_laya(*args, **kwargs):
        result = await _uvicorn_shutdown(*args, **kwargs)
        _stop_laya_server()
        return result

    _server.shutdown = _shutdown_and_stop_laya
    _server.run()
