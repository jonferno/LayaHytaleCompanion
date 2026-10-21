# Installing Laya

## 1. Install Hytale and Python

- Install Hytale through its official launcher and run it once.
- Install Python 3.11 from <https://www.python.org/downloads/>. Tick **Add
  Python to PATH** during installation.
- Java 25 is only needed if you build the plugin from source.

## 2. Install the required laya.cpp engine

`laya.cpp` is not optional. The bridge refuses to start without it.

1. Download a Windows Vulkan build from
   <https://github.com/lkarlslund/laya.cpp/releases>.
2. Put the executable and the `typed-decisions` checkpoint in
   `ai-bridge\laya\`.
3. Confirm the layout looks like this:

   ```text
   ai-bridge\laya\
     laya-r*-windows-amd64-vulkan.exe
     typed-decisions\
   ```

The installer validates both paths and stops with a clear error if either is
missing.

## 3. Run the installer

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

1. Validates the required `laya.cpp` executable and checkpoint.
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

This opens <http://127.0.0.1:8788>. Paste a Groq, Google AI Studio, or z.ai key
into the settings page and save. You only need one provider. The key is written
to `config\bridge-config.json` on your machine and is never part of the repo.

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
