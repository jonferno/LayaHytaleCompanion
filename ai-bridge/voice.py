"""
In-process voice helper for the Laya AI Bridge.
Merges the old standalone capture.py into the bridge so we run ONE Python
process (and one PyTorch) instead of two — saves ~2 GB RAM.

Threads started from FastAPI startup:
  - talk-key loop: hold key -> record mic -> Parakeet STT -> run_chat -> play voice
  - external-say poller: plays sentence WAVs from logs/laya-say/ as they appear
"""

import base64
import io
import threading
import time
from pathlib import Path

import httpx
import numpy as np
import sounddevice as sd
import soundfile as sf

try:
    import keyboard
    HAS_KEYBOARD = True
except ImportError:
    HAS_KEYBOARD = False

# NeMo is imported lazily inside STT.warm() — a top-level import blocks
# the server startup event for ~20s while torch/NeMo load.

SAMPLE_RATE = 16000
SAY_DIR = Path(__file__).resolve().parent.parent / "logs" / "laya-say"


def _notify_talk(cfg, on):
    """Tell the Hytale plugin the voice helper is starting/stopping audio."""
    try:
        url = cfg.get("plugin_bridge_url", "http://127.0.0.1:8787/command")
        with httpx.Client(timeout=1.5) as client:
            client.post(url, json={"talk": "start" if on else "stop"})
    except Exception as e:
        print(f"[Laya Voice] talk signal failed: {e}", flush=True)


class STT:
    """Parakeet — ONNX int8 (~1 GB incl. Kokoro, no torch), lazy-warmed in background."""

    def __init__(self, model_dir: str = None):
        self.model = None
        self.ready = False
        self.loading = False
        self.model_dir = model_dir or str(Path(__file__).resolve().parent.parent / "models" / "parakeet-onnx")

    def warm(self):
        if self.loading or self.ready:
            return
        self.loading = True
        try:
            log("Loading Parakeet STT (ONNX int8, ~4s)...")
            t0 = time.time()
            import onnx_asr
            self.model = onnx_asr.load_model(
                "nemo-parakeet-tdt-0.6b-v2",
                path=self.model_dir,
                providers=["CPUExecutionProvider"],
                quantization="int8",
            )
            self.ready = True
            log(f"Parakeet ready in {time.time()-t0:.0f}s (ONNX int8)")
        except Exception as e:
            log(f"Parakeet load failed: {e}")

    def transcribe(self, audio: np.ndarray) -> str:
        if not self.ready:
            return ""
        try:
            audio = np.asarray(audio, dtype=np.float32).reshape(-1)
            out = self.model.recognize(audio)
            return out if isinstance(out, str) else str(out)
        except Exception as e:
            log(f"STT error: {e}")
            return ""


# Late-bound log from main (set in start())
log = lambda msg: print(f"[Laya Voice] {msg}", flush=True)


def start(cfg, run_chat_fn, loop):
    """Start the in-process voice helper. Called from FastAPI startup."""
    threading.Thread(target=_talk_loop, args=(cfg, run_chat_fn, loop), daemon=True).start()
    threading.Thread(target=_poll_say_queue, args=(cfg,), daemon=True).start()
    threading.Thread(target=_tp_loop, args=(cfg,), daemon=True).start()
    log(f"Voice helper started (talk='{cfg.get('talk_key','x')}', tp='{cfg.get('tp_key','f8')}')")


def _tp_loop(cfg):
    """Unstick hotkey: press tp_key -> POST /tp to the plugin bridge -> Laya teleports to player."""
    if not HAS_KEYBOARD:
        return
    tp_key = cfg.get("tp_key", "f8")
    url = "http://127.0.0.1:8787/tp"
    while True:
        try:
            while not keyboard.is_pressed(tp_key):
                time.sleep(0.05)
            print(f"[Laya Voice] {tp_key.upper()} pressed — teleporting Laya to you", flush=True)
            with httpx.Client(timeout=5.0) as client:
                r = client.post(url)
                print(f"[Laya Voice] /tp -> {r.status_code}", flush=True)
            # debounce: wait for release
            while keyboard.is_pressed(tp_key):
                time.sleep(0.05)
            time.sleep(0.3)
        except Exception as e:
            print(f"[Laya Voice] tp hotkey error: {e}", flush=True)
            time.sleep(1.0)


def _talk_loop(cfg, run_chat_fn, loop):
    if not HAS_KEYBOARD:
        log("keyboard module missing — voice disabled")
        return
    talk_key = cfg.get("talk_key", "x")
    player = cfg.get("player_name", "Jonferno")
    mode = str(cfg.get("talk_mode", "hold")).strip().lower()
    name = cfg.get("companion_name", "Laya")
    stt = STT()
    # warm STT in background so the first press is fast
    threading.Thread(target=stt.warm, daemon=True).start()
    log(f"{'Toggle' if mode == 'toggle' else 'Hold'} '{talk_key.upper()}' to talk to {name}.")
    if talk_key == cfg.get("tp_key", "f8"):
        log(f"Warning: talk key and teleport key are both '{talk_key}'.")

    try:
        while True:
            # --- edge detection: wait for a press (and, in toggle mode, its release
            # before this press is armed to START recording; a release-wait is
            # mandatory because keyboard.is_pressed is a level check and would
            # otherwise count one press as many). ---
            while not keyboard.is_pressed(talk_key):
                time.sleep(0.03)
            if mode == "toggle":
                # debounce: wait for the start press to be released
                while keyboard.is_pressed(talk_key):
                    time.sleep(0.03)
                time.sleep(0.3)
            frames = []
            def cb(indata, frames_n, time_info, status):
                frames.append(indata.copy())
            with sd.InputStream(samplerate=SAMPLE_RATE, channels=1, dtype="float32",
                                callback=cb, device=cfg.get("input_device")):
                if mode == "toggle":
                    print(f"[Laya Voice] Recording... (press {talk_key.upper()} again to send)", flush=True)
                    started = time.time()
                    # wait for the NEXT press, then its release
                    while True:
                        if keyboard.is_pressed(talk_key):
                            while keyboard.is_pressed(talk_key):
                                time.sleep(0.03)
                            break
                        if time.time() - started >= 120:
                            print("[Laya Voice] Toggle recording hit the 120s cap", flush=True)
                            break
                        time.sleep(0.03)
                    time.sleep(0.3)
                else:
                    print(f"[Laya Voice] Recording... (release {talk_key.upper()} to send)", flush=True)
                    while keyboard.is_pressed(talk_key):
                        time.sleep(0.03)
            if not frames:
                continue
            audio = np.concatenate(frames, axis=0).reshape(-1)
            level = float(np.abs(audio).max())
            print(f"[Laya Voice] Captured {len(audio)/SAMPLE_RATE:.1f}s, mic level {level:.4f}", flush=True)
            if level < 0.01:
                print("[Laya Voice] Mic level too low.", flush=True)
                continue
            if not stt.ready:
                print("[Laya Voice] STT still loading — wait for 'Parakeet ready'.", flush=True)
                while not stt.ready and stt.loading:
                    time.sleep(0.5)
                if not stt.ready:
                    continue
            text = stt.transcribe(audio)
            if not text or not text.strip():
                print("[Laya Voice] No speech recognized.", flush=True)
                continue
            print(f"[Laya Voice] You said: {text}", flush=True)
            # Direct in-process chat (no HTTP self-call)
            fut = __import__("asyncio").run_coroutine_threadsafe(
                run_chat_fn(player, text.strip(), {
                    "player": {"name": player}, "nearby_entities": [],
                    "time_of_day": "day", "laya": {"alive": True},
                }, True, user_text_turn=True),
                loop,
            )
            try:
                resp = fut.result(timeout=30)
            except Exception as e:
                print(f"[Laya Voice] Chat failed: {e}", flush=True)
                continue
            print(f"[Laya Voice] Laya: {resp.say}", flush=True)
            # Voice turns call the LLM in-process, so they bypass ChatListener.
            # Push the SAME response to the plugin's /command surface, or she
            # only *says* she will mine/follow and nothing ever executes.
            try:
                cmd = resp.model_dump() if hasattr(resp, "model_dump") else dict(resp)
                cmd.pop("audio_b64", None)
                cmd["silent"] = True          # voice turn: she speaks it, don't print to chat
                cmd_url = cfg.get("plugin_bridge_url", "http://127.0.0.1:8787/command")
                with httpx.Client(timeout=5.0) as client:
                    cr = client.post(cmd_url, json=cmd)
                print(f"[Laya Voice] /command -> {cr.status_code} "
                      f"(action={cmd.get('action')} task={cmd.get('task')} count={cmd.get('count')})",
                      flush=True)
            except Exception as e:
                print(f"[Laya Voice] /command failed: {e}", flush=True)
            if resp.audio_b64:
                try:
                    data, sr = sf.read(io.BytesIO(base64.b64decode(resp.audio_b64)), dtype="float32")
                    _notify_talk(cfg, True)
                    try:
                        sd.play(data, sr, device=cfg.get("output_device"))
                        sd.wait()
                    finally:
                        _notify_talk(cfg, False)
                except Exception as e:
                    print(f"[Laya Voice] Playback failed: {e}", flush=True)
            time.sleep(0.3)
    except Exception as e:
        print(f"[Laya Voice] Talk loop crashed: {e}", flush=True)


def _poll_say_queue(cfg):
    """Play sentence WAVs from logs/laya-say/ in order as they are written.

    The bridge writes one atomically-replaced file per sentence, so playback can
    start after the first sentence while the rest are still synthesizing.
    Files present at startup are treated as stale and skipped.
    """
    seen = set()
    try:
        SAY_DIR.mkdir(parents=True, exist_ok=True)
        seen.update(p.name for p in SAY_DIR.glob("*.wav"))
    except Exception as e:
        print(f"[Laya Voice] say-queue init error: {e}", flush=True)
    while True:
        try:
            files = sorted(SAY_DIR.glob("*.wav")) if SAY_DIR.exists() else []
            for f in files:
                if f.name in seen:
                    continue
                seen.add(f.name)
                try:
                    data, sr = sf.read(str(f), dtype="float32")
                    print(f"[Laya Voice] Laya (typed chat): playing {len(data)/sr:.1f}s", flush=True)
                    _notify_talk(cfg, True)
                    try:
                        sd.play(data, sr, device=cfg.get("output_device"))
                        sd.wait()
                    finally:
                        _notify_talk(cfg, False)
                finally:
                    try:
                        f.unlink()
                    except Exception:
                        pass
        except Exception as e:
            print(f"[Laya Voice] say-queue poll error: {e}", flush=True)
        time.sleep(0.1)
