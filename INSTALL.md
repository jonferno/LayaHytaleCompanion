# Installing Laya

## 1. Install Hytale and Python

- Install Hytale through its official launcher and run it once.
- Install Python 3.11 from <https://www.python.org/downloads/>. Tick **Add
  Python to PATH** during installation.
- Java 25 is only needed if you build the plugin from source.
- Deepgram Aura is the default voice and needs a Deepgram API key.
- Free API keys: [Groq](https://console.groq.com/keys),
  [z.ai](https://z.ai/manage-apikey/apikey), and
  [Deepgram](https://console.deepgram.com/api-keys). The optional uncensored
  primary uses a paid [NanoGPT](https://nano-gpt.com/api) key.
- Kokoro TTS and Parakeet STT are optional local backups; their ~1 GB model
  download is not required for Deepgram.

## 2. Install the required laya.cpp engine

`laya.cpp` is not optional, and you no longer download it by hand: the installer
fetches the right build for your GPU.

When it asks, pick a backend:

- **Vulkan**: NVIDIA, AMD or Intel (about 77 MB)
- **CUDA 13**: NVIDIA with a current driver, the fastest option (about 263 MB plus two DLLs)
- **CUDA 12**: V100 or an older NVIDIA driver (about 296 MB plus two DLLs)

It places the engine and the `typed-decisions` checkpoint (about 800 MB) in
`ai-bridge\laya\`:

```text
ai-bridge\laya\
  laya-r*-windows-amd64-vulkan.exe    (or -cuda13.exe / -cuda12.exe)
  typed-decisions\
```

To fetch or switch the engine yourself, run the same downloader the installer uses:

```powershell
python download-laya.py --backend vulkan
python download-laya.py --backend cuda13
python download-laya.py --backend cuda12 --skip-model
```

It resumes interrupted downloads, verifies the release checksum, skips files that
are already present, and points `config\bridge-config.json` at the build you
chose (including the correct backend flag).

## 3. Run the installer

From this folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

Useful options:

```powershell
# Hytale is somewhere other than D:\Games\Hytale
.\install.ps1 -HytalePath "C:\Games\Hytale"

# Choose the GPU backend without being asked (vulkan, cuda13, cuda12, skip)
.\install.ps1 -LayaBackend cuda13

# Keep an existing typed-decisions checkpoint
.\install.ps1 -LayaBackend vulkan -SkipLayaModel

# Skip the optional ~1 GB local speech models (Deepgram still works)
.\install.ps1 -SkipModels

# Keep Parakeet STT but skip the optional Kokoro TTS model
.\install.ps1 -SkipKokoro

# Skip pip because dependencies are already installed
.\install.ps1 -SkipPython
```

The installer:

1. Downloads the selected `laya.cpp` build and the `typed-decisions` checkpoint,
   or validates the ones already in `ai-bridge\laya`.
2. Finds `UserData\Mods` under your Hytale folder.
3. Copies `dist\LayaPlugin-*.jar` into that folder.
4. Creates `config\bridge-config.json` from the example with **empty API keys**.
5. Sets user environment variables so the plugin can find `config\` and `logs\`.
6. Installs `ai-bridge\requirements.txt`.
7. Runs `download-models.py` unless `-SkipModels` is supplied.

Restart Hytale after the first install so it picks up the user environment
variables.

## 4. Configure the AI provider

Start the bridge:

```powershell
.\start.bat
```

This opens <http://127.0.0.1:8788>. Paste a NanoGPT, Groq, Google AI Studio, or
z.ai key for the brain, and a Deepgram key for the default voice. You only need
one LLM provider. NanoGPT is tried first when configured and uses the uncensored
`gemma-4-26b-a4b-it-chimeraxheretic` model by default; the other providers are
fallbacks. The keys are written to `config\bridge-config.json` on your machine
and are never part of the repo.

Deepgram is the first-choice voice because it sounded the best in testing. The
project has $200 of starter credits; Edge and Kokoro are fallback voices, and
Kokoro's local models are optional.

If you prefer to edit the file directly, copy
`config\bridge-config.example.json` to `config\bridge-config.json` and fill in
one key. Keep the file private.

The bridge will not start if the laya.cpp server or `typed-decisions` checkpoint
is missing.

## 5. Play

1. Keep the bridge window running.
2. Start Hytale and enter a world.
3. Chat with Laya or use `/laya`.

## Manual setup

If you do not want to use the PowerShell installer:

```powershell
# laya.cpp executable + typed-decisions must already be in ai-bridge\laya
python -m pip install -r .\ai-bridge\requirements.txt
python .\download-models.py
Copy-Item .\dist\LayaPlugin-*.jar "$Hytale\UserData\Mods\"
Copy-Item .\config\bridge-config.example.json .\config\bridge-config.json
```

Then set these user environment variables to absolute paths:

| Variable | Value |
| --- | --- |
| `LAYA_BLOCKS_JSON` | `<repo>\config\blocks.json` |
| `LAYA_STATE_JSON` | `<repo>\config\laya-inventory.json` |
| `LAYA_SKIN_JSON` | `<repo>\config\laya-skin.json` |
| `LAYA_COMBAT_JSON` | `<repo>\config\laya-combat.json` |
| `LAYA_LOG` | `<repo>\logs\laya-plugin.log` |

The inventory, skin, and combat files are created automatically when needed.
For a completely fresh character, delete those files while Hytale is closed.
