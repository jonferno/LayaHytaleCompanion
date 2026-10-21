# Laya - AI Companion for Hytale

Laya is a local-first AI companion mod for Hytale. She follows you, talks with
you, remembers useful facts, mines and gathers with your guidance, and fights
beside you. The mod is a Hytale Java plugin plus a small Python AI bridge.

**This repository contains no API keys and no personal game data.** Keys are
entered locally in the settings page and are stored in
`config/bridge-config.json`, which is git-ignored. A fresh install starts with
an empty inventory, no memory, and no saved appearance.

## What you need

- Windows 10/11
- Hytale 0.7.x
- Python 3.11
- Java 25 (only if you want to build the plugin yourself)
- An API key from Groq, Google AI Studio, or z.ai for cloud replies
- About 1 GB free for the local speech models

The optional `laya.cpp` decision engine is downloaded separately from its
GitHub releases page. Without it, Laya uses the built-in rule-based decision
fallback.

## Quick install

1. Clone or download this repository.
2. Double-click `install.bat`, or run:

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1
   ```

   The installer asks for your Hytale folder (usually `D:\Games\Hytale`),
   copies the plugin JAR, creates a local config with **empty keys**, installs
   the Python dependencies, and downloads the speech models unless you pass
   `-SkipModels`.

3. Run `start.bat`, open <http://127.0.0.1:8788>, and paste your API key into
   the settings page. Save the settings.
4. Start Hytale. The installer sets the plugin's config paths as user
   environment variables, so restart Hytale once after the first install.
5. In game, talk to Laya in chat or use `/laya`.

See [INSTALL.md](INSTALL.md) for details, manual setup, and troubleshooting.

## How it works

- `hytale-plugin/` is the Java plugin. It exposes a local HTTP bridge on
  `127.0.0.1:8787` for commands and HUD updates.
- `ai-bridge/` is the Python sidecar. It handles speech-to-text, the LLM,
  text-to-speech, memory, and the optional `laya.cpp` process.
- `config/blocks.json` maps player words such as "oak" or "iron" to Hytale
  block names.
- `config/hytale-knowledge.json` is the small game-knowledge reference used in
  prompts.

## Building the plugin

The repository ships `dist/LayaPlugin-0.2.0.jar` for convenience. To rebuild it
against your installed Hytale version:

```powershell
$env:HYTALE_SERVER_JAR = "D:\Games\Hytale\install\release\package\game\latest\Server\HytaleServer.jar"
cd hytale-plugin
gradle build
```

The Gradle build uses `HYTALE_SERVER_JAR` and optionally `HYTALE_MODS_DIR`; it
does not contain machine-specific paths.

Hytale 0.7.3 may require a rebuild if the server API changed. Rebuild before
reporting plugin-loading problems.

## Privacy and safety

- No keys, personal skins, inventories, combat state, or chat memory are
  committed.
- `config/bridge-config.json`, `config/laya-*.json`, `logs/`, `models/`, and
  `ai-bridge/laya/` are git-ignored.
- The bridge listens on localhost only by default.
- API keys are only sent to the provider you configure.

## License and credits

Plugin and bridge code by Jonferno. Built with Hytale's plugin API, FastAPI,
Kokoro, Parakeet, and optional `laya.cpp`. See the respective upstream projects
for their licenses.
