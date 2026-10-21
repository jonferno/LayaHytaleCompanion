# Installing Laya

## 1. Install Hytale and Python

- Install Hytale through its official launcher and run it once.
- Install Python 3.11 from <https://www.python.org/downloads/>. Tick **Add
  Python to PATH** during installation.
- Java 25 is only needed if you build the plugin from source.

## 2. Run the installer

From this folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\install.ps1
```

Useful options:

```powershell
# Hytale is somewhere other than D:\Games\Hytale
.\install.ps1 -HytalePath "C:\Games\Hytale"

# Skip the ~1 GB model download for now
.\install.ps1 -SkipModels

# Skip pip because dependencies are already installed
.\install.ps1 -SkipPython
```

The installer:

1. Finds `UserData\Mods` under your Hytale folder.
2. Copies `dist\LayaPlugin-*.jar` into that folder.
3. Creates `config\bridge-config.json` from the example with **empty API keys**.
4. Sets user environment variables so the plugin can find `config\` and `logs\`.
5. Installs `ai-bridge\requirements.txt`.
6. Runs `download-models.py` unless `-SkipModels` is supplied.

Restart Hytale after the first install so it picks up the user environment
variables.

## 3. Configure the AI provider

Start the bridge:

```powershell
.\start.bat
```

This opens <http://127.0.0.1:8788>. Paste a Groq, Google AI Studio, or z.ai key
into the settings page and save. You only need one provider. The key is written
to `config\bridge-config.json` on your machine and is never part of the repo.

If you prefer to edit the file directly, copy
`config\bridge-config.example.json` to `config\bridge-config.json` and fill in
one key. Keep the file private.

## 4. Install the optional decision engine

Laya works without this, using her rule-based fallback. For the local
`laya.cpp` decision engine:

1. Download a Windows Vulkan build from
   <https://github.com/lkarlslund/laya.cpp/releases>.
2. Put the executable and the `typed-decisions` checkpoint in
   `ai-bridge\laya\`.
3. In the settings page, set `decision_engine` to `laya` and confirm the binary
   and checkpoint paths. The defaults point at `ai-bridge\laya\`.

## 5. Play

1. Keep the bridge window running.
2. Start Hytale and enter a world.
3. Chat with Laya or use `/laya`.

## Manual setup

If you do not want to use the PowerShell installer:

```powershell
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

## Troubleshooting

- **Plugin does not load**: check that the JAR is in
  `UserData\Mods`, then check `logs\laya-plugin.log`. Rebuild the JAR if you
  updated Hytale to a version with a changed server API.
- **Port already in use**: the plugin uses 8787 and the bridge uses 8788. Close
  old Python/Java processes and retry.
- **No voice**: verify `models\kokoro-onnx\` contains the ONNX model and voices
  file, and that the selected TTS engine is `kokoro` or `edge`.
- **No speech recognition**: verify `models\parakeet-onnx\` exists; the
  installer downloads it. Microphone access must be allowed for Python.
- **Cloud replies fail**: re-check the API key in the settings page. The bridge
  logs the provider error to `logs\bridge.log`.

## Reset for a new character

Close Hytale and the bridge, then run:

```powershell
.\reset-data.ps1
```

This deletes `config\laya-inventory.json`, `config\laya-skin.json`,
`config\laya-combat.json`, and `logs\laya-memory.json`. Your API keys in
`config\bridge-config.json` are kept. The next launch creates clean defaults.
