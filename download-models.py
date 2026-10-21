"""
Laya Companion - model downloader.

Downloads the local speech models used by the AI bridge:
  - Kokoro-82M ONNX text-to-speech
  - Parakeet TDT 0.6B v2 ONNX speech-to-text

Run from the repository root:
    python download-models.py

The laya.cpp decision engine is required and must be installed separately.
See INSTALL.md.
"""

import argparse
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
MODELS_DIR = ROOT / "models"
LAYA_DIR = ROOT / "ai-bridge" / "laya"

KOKORO_BASE = (
    "https://github.com/thewh1teagle/kokoro-onnx/releases/download/"
    "model-files-v1.0"
)
KOKORO_FILES = ["kokoro-v1.0.fp16.onnx", "voices-v1.0.bin"]
PARAKEET_REPO = "istupakov/parakeet-tdt-0.6b-v2-onnx"


def download_url(url: str, target: Path) -> bool:
    target.parent.mkdir(parents=True, exist_ok=True)
    if target.exists() and target.stat().st_size > 0:
        print(f"  [skip] {target.name} ({target.stat().st_size / 1e6:.0f} MB)")
        return True
    print(f"  [get ] {target.name}")
    started = time.time()
    try:
        urllib.request.urlretrieve(url, target)
        print(f"  [ok  ] {target.name} in {time.time() - started:.0f}s")
        return True
    except Exception as exc:
        print(f"  [fail] {target.name}: {exc}")
        if target.exists():
            target.unlink(missing_ok=True)
        return False


def download_kokoro() -> bool:
    out_dir = MODELS_DIR / "kokoro-onnx"
    print(f"\nKokoro TTS -> {out_dir}")
    ok = True
    for name in KOKORO_FILES:
        ok = download_url(f"{KOKORO_BASE}/{name}", out_dir / name) and ok
    return ok


def download_parakeet() -> bool:
    out_dir = MODELS_DIR / "parakeet-onnx"
    print(f"\nParakeet STT -> {out_dir}")
    try:
        from huggingface_hub import snapshot_download

        snapshot_download(
            repo_id=PARAKEET_REPO,
            local_dir=str(out_dir),
            allow_patterns=["*int8*", "nemo128.onnx", "config.json", "vocab.txt"],
        )
        print("  [ok  ] Parakeet ONNX files")
        return True
    except Exception as exc:
        print(f"  [fail] Parakeet download: {exc}")
        return False


def main() -> int:
    parser = argparse.ArgumentParser(description="Download optional local speech models.")
    parser.add_argument("--skip-kokoro", action="store_true",
                        help="skip the Kokoro TTS model (Deepgram still works)")
    args = parser.parse_args()
    print("=" * 60)
    print("  Laya Companion - model downloader")
    print("=" * 60)
    MODELS_DIR.mkdir(parents=True, exist_ok=True)
    LAYA_DIR.mkdir(parents=True, exist_ok=True)

    kokoro_ok = True if args.skip_kokoro else download_kokoro()
    parakeet_ok = download_parakeet()
    results = [kokoro_ok, parakeet_ok]

    print("\nRequired decision engine")
    print("  laya.cpp is not downloaded automatically.")
    print("  Get a Windows Vulkan build and the typed-decisions checkpoint from:")
    print("    https://github.com/lkarlslund/laya.cpp/releases")
    print(f"  Put them in: {LAYA_DIR}")
    laya_exe = list(LAYA_DIR.glob("*windows-amd64-vulkan.exe"))
    laya_checkpoint = LAYA_DIR / "typed-decisions"
    laya_ok = bool(laya_exe) and laya_checkpoint.exists()
    if not laya_ok:
        print("  [MISSING] laya.cpp is REQUIRED; the bridge will not start without it.")

    print("\nSummary")
    print(f"  Kokoro:   {'SKIPPED' if args.skip_kokoro else ('OK' if results[0] else 'MISSING')}")
    print(f"  Parakeet: {'OK' if results[1] else 'MISSING'}")
    print(f"  laya.cpp: {'OK' if laya_ok else 'MISSING (required)'}")
    print("\nNext: start the bridge with start.bat and add an API key in Settings.")
    return 0 if all(results) and laya_ok else 1


if __name__ == "__main__":
    sys.exit(main())
