"""Download the laya.cpp engine and the typed-decisions checkpoint.

This is what the installer calls so a new player never has to visit two GitHub
releases and a Hugging Face page by hand. It is standard-library only, so it runs
before the Python dependencies are installed.

    python download-laya.py --backend vulkan
    python download-laya.py --backend cuda13
    python download-laya.py --backend cuda12 --skip-model

Backends:
    vulkan  works on NVIDIA, AMD and Intel
    cuda13  NVIDIA, current driver (CUDA 13 build, needs two cuBLAS DLLs beside the exe)
    cuda12  NVIDIA V100 or an older driver (CUDA 12 build, same two DLLs)

The engine comes from the laya.cpp releases, the checkpoint from the pinned
Hugging Face revision laya.cpp itself uses. Existing files with the expected size
are skipped, so running this twice is safe.

Afterwards, config/bridge-config.json (when present) is pointed at the chosen
executable and backend flag, because a stale `--vulkan` argument makes a CUDA
build fail to start.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
DEFAULT_DEST = ROOT / "ai-bridge" / "laya"

RELEASES_API = "https://api.github.com/repos/lkarlslund/laya.cpp/releases"
RELEASE_DOWNLOAD = "https://github.com/lkarlslund/laya.cpp/releases/download"

# Pinned by laya.cpp's own scripts/download_model.py. A newer engine release still
# reads this checkpoint; move both together rather than one at a time.
HF_REPO = "convaiinnovations/laya"
HF_REVISION = "1c5edc17a7acd8701df6fc341c0d179f1c62c982"
MODEL_FILES = [
    "model.safetensors",
    "rl_agent_config.json",
    "encoder/config.json",
    "tokenizer/tokenizer.json",
    "tokenizer/tokenizer_config.json",
]

# NVIDIA's cuBLAS redistribution archives, exactly as laya.cpp's
# RUNTIME-REQUIREMENTS.md names them for each CUDA major.
CUBLAS = {
    "cuda13": {
        "url": "https://developer.download.nvidia.com/compute/cuda/redist/libcublas/"
               "windows-x86_64/libcublas-windows-x86_64-13.1.0.3-archive.zip",
        "sha256": "4ac4847bbe4f7709b244956fcfc32197a2954ee70b155cb67eebd9ee26f7e339",
        "major": "13",
    },
    "cuda12": {
        "url": "https://developer.download.nvidia.com/compute/cuda/redist/libcublas/"
               "windows-x86_64/libcublas-windows-x86_64-12.9.1.4-archive.zip",
        "sha256": "d534d98b0b453a98914dbf3adf47d7e84b55037abf02f87466439e1dcef581ed",
        "major": "12",
    },
}

BACKEND_ASSET = {
    "vulkan": "windows-amd64-vulkan.exe",
    "cuda13": "windows-amd64-cuda13.exe",
    "cuda12": "windows-amd64-cuda12.exe",
}

UA = {"User-Agent": "LayaHytaleCompanion-installer"}


def log(msg: str = "") -> None:
    print(msg, flush=True)


def human(n: float) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024 or unit == "GB":
            return f"{n:.0f} {unit}" if unit == "B" else f"{n:.1f} {unit}"
        n /= 1024
    return f"{n:.1f} GB"


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def get_json(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return json.load(r)


def get_text(url: str) -> str:
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read().decode("utf-8", "replace")


def download(url: str, dest: Path, expected_size: int | None = None,
             expect_sha256: str | None = None, label: str | None = None) -> Path:
    """Stream to <dest>.part, resume when possible, verify, then rename to dest."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    name = label or dest.name
    if dest.is_file() and (expected_size is None or dest.stat().st_size == expected_size):
        log(f"  {name}: already present, skipping")
        return dest

    part = dest.with_name(dest.name + ".part")
    have = part.stat().st_size if part.is_file() else 0
    headers = dict(UA)
    if have:
        headers["Range"] = f"bytes={have}-"
    req = urllib.request.Request(url, headers=headers)
    try:
        r = urllib.request.urlopen(req, timeout=60)
    except urllib.error.HTTPError as e:
        if e.code == 416 and part.is_file():
            part.replace(dest)
            return dest
        raise
    status = getattr(r, "status", 200)
    total = None
    if status == 206:
        cr = r.headers.get("Content-Range", "")
        if "/" in cr:
            total = int(cr.rsplit("/", 1)[1])
        mode = "ab"
    else:
        have = 0
        cl = r.headers.get("Content-Length")
        total = int(cl) if cl else expected_size
        mode = "wb"
    log(f"  {name}: {human(total) if total else 'unknown size'}")
    done = have
    with open(part, mode) as fh:
        while True:
            chunk = r.read(1024 * 1024)
            if not chunk:
                break
            fh.write(chunk)
            done += len(chunk)
            if total:
                pct = done * 100 // total
                sys.stdout.write(f"\r  {name}: {pct:3d}%  {human(done)} / {human(total)}")
                sys.stdout.flush()
    r.close()
    sys.stdout.write("\r" + " " * 78 + "\r")
    sys.stdout.flush()

    size = part.stat().st_size
    if expected_size is not None and size != expected_size:
        part.unlink(missing_ok=True)
        raise RuntimeError(f"{name}: expected {expected_size} bytes, got {size}")
    if expect_sha256:
        got = sha256_file(part)
        if got.lower() != expect_sha256.lower():
            part.unlink(missing_ok=True)
            raise RuntimeError(f"{name}: sha256 mismatch (got {got})")
    part.replace(dest)
    log(f"  {name}: done ({human(size)})")
    return dest


def pick_release(pin: str | None) -> str:
    """Newest published release tag (laya.cpp marks its rolling builds prerelease)."""
    if pin:
        return pin
    releases = get_json(RELEASES_API + "?per_page=10")
    for rel in releases:
        if rel.get("draft"):
            continue
        tag = rel.get("tag_name") or ""
        if any((a.get("name") or "").endswith("windows-amd64-vulkan.exe")
               for a in rel.get("assets", [])):
            return tag
    raise RuntimeError("No laya.cpp release with Windows assets found")


def download_engine(backend: str, tag: str, dest: Path, dry: bool) -> Path:
    asset = f"laya-{tag}-{BACKEND_ASSET[backend]}"
    url = f"{RELEASE_DOWNLOAD}/{tag}/{asset}"
    target = dest / asset
    log(f"Engine: {asset}")
    if dry:
        log(f"  would download {url}")
        return target
    expected = None
    sums_url = f"{RELEASE_DOWNLOAD}/{tag}/SHA256SUMS"
    try:
        for line in get_text(sums_url).splitlines():
            bits = line.split()
            if len(bits) >= 2 and bits[-1].lstrip("*") == asset:
                expected = bits[0]
                break
    except Exception as e:
        log(f"  (checksum list unavailable: {e})")
    download(url, target, expect_sha256=expected, label=asset)
    return target


def download_cublas(backend: str, dest: Path, dry: bool) -> None:
    info = CUBLAS[backend]
    major = info["major"]
    want = [f"cublas64_{major}.dll", f"cublasLt64_{major}.dll"]
    if all((dest / w).is_file() for w in want):
        log(f"cuBLAS {major}: already beside the executable")
        return
    log(f"cuBLAS {major}: the CUDA build needs two NVIDIA DLLs")
    if dry:
        log(f"  would download {info['url']}")
        return
    cache = dest / ".cache"
    zip_path = cache / Path(info["url"]).name
    download(info["url"], zip_path, expect_sha256=info["sha256"], label=zip_path.name)
    found = 0
    with zipfile.ZipFile(zip_path) as zf:
        for member in zf.namelist():
            base = member.rsplit("/", 1)[-1]
            if base in want:
                with zf.open(member) as src, open(dest / base, "wb") as out:
                    shutil.copyfileobj(src, out)
                found += 1
    zip_path.unlink(missing_ok=True)
    try:
        cache.rmdir()
    except OSError:
        pass
    if found != len(want):
        raise RuntimeError(f"cuBLAS archive did not contain {', '.join(want)}")
    log(f"cuBLAS {major}: {', '.join(want)} placed beside the executable")


def download_model(dest: Path, dry: bool, revision: str = HF_REVISION) -> Path:
    target = dest / "typed-decisions"
    base = f"https://huggingface.co/{HF_REPO}/resolve/{revision}/typed-decisions"
    log(f"Checkpoint: {HF_REPO} @ {revision[:8]}")
    for name in MODEL_FILES:
        url = f"{base}/{name}"
        out = target / name
        expected = None
        try:
            req = urllib.request.Request(url, method="HEAD", headers=UA)
            with urllib.request.urlopen(req, timeout=60) as r:
                cl = r.headers.get("Content-Length")
                expected = int(cl) if cl else None
        except Exception:
            pass
        if dry:
            log(f"  would download {url}" + (f" ({human(expected)})" if expected else ""))
            continue
        download(url, out, expected_size=expected, label=f"typed-decisions/{name}")
    return target


def update_config(dest: Path, exe: Path, checkpoint: Path, backend: str, dry: bool) -> None:
    cfg_path = ROOT / "config" / "bridge-config.json"
    if not cfg_path.is_file():
        return
    try:
        cfg = json.loads(cfg_path.read_text(encoding="utf-8"))
    except Exception as e:
        log(f"Config: could not read {cfg_path}: {e}")
        return
    port = "8090"
    old_args = cfg.get("laya_server_args") or []
    for i, a in enumerate(old_args):
        if a == "--port" and i + 1 < len(old_args):
            port = str(old_args[i + 1])
    args = ["--server", "--host", "127.0.0.1", "--port", port,
            "--model", str(checkpoint)]
    if backend == "vulkan":
        args.insert(0, "--vulkan")
    cfg["laya_server_exe"] = str(exe)
    cfg["laya_binary"] = str(exe)
    cfg["laya_checkpoint"] = str(checkpoint)
    cfg["laya_server_args"] = args
    if dry:
        log(f"Config: would point {cfg_path} at {exe.name} ({backend})")
        return
    cfg_path.write_text(json.dumps(cfg, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    log(f"Config: {cfg_path.name} now uses {exe.name} ({backend})")


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--backend", choices=sorted(BACKEND_ASSET), required=True)
    ap.add_argument("--dest", default=str(DEFAULT_DEST))
    ap.add_argument("--release", default=None,
                    help="laya.cpp release tag (default: newest with Windows assets)")
    ap.add_argument("--revision", default=HF_REVISION,
                    help="Hugging Face checkpoint revision")
    ap.add_argument("--skip-engine", action="store_true")
    ap.add_argument("--skip-model", action="store_true")
    ap.add_argument("--skip-cublas", action="store_true",
                    help="CUDA only: assume the cuBLAS DLLs are already on PATH")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--no-config", action="store_true")
    a = ap.parse_args()

    dest = Path(a.dest)
    dest.mkdir(parents=True, exist_ok=True)

    if a.skip_engine:
        exes = sorted(dest.glob("laya-*-windows-amd64-*.exe"),
                      key=lambda p: p.stat().st_mtime, reverse=True)
        if not exes:
            raise SystemExit(f"No laya.cpp executable in {dest}")
        exe = exes[0]
        log(f"Engine: keeping {exe.name}")
    else:
        tag = pick_release(a.release)
        log(f"laya.cpp release: {tag}")
        exe = download_engine(a.backend, tag, dest, a.dry_run)
        if a.backend != "vulkan" and not a.skip_cublas:
            download_cublas(a.backend, dest, a.dry_run)

    checkpoint = dest / "typed-decisions"
    if a.skip_model:
        log(f"Checkpoint: keeping {checkpoint}")
    else:
        checkpoint = download_model(dest, a.dry_run, a.revision)

    if not a.no_config:
        update_config(dest, exe if not a.dry_run else Path(exe), checkpoint,
                      a.backend, a.dry_run)

    log("")
    log(f"Engine:     {exe}")
    log(f"Checkpoint: {checkpoint}")
    log("Done. Start the bridge with start.bat.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
