<p align="center">
  <img src="assets/banner.jpg" alt="Laya Hytale Companion banner" width="100%">
</p>

<p align="center">
  A 100% Free AI powered companion for Hytale: it follows, talks, remembers, mines,
  gathers, farms, harvests, and fights beside you.
</p>

<p align="center">
  <code>Hytale 0.7.x</code> &nbsp;|&nbsp; <code>Windows</code> &nbsp;|&nbsp;
  <code>Python 3.11</code> &nbsp;|&nbsp; <code>laya.cpp required</code>
</p>

<p align="center">
  <strong>Tested on Hytale 0.7.4.</strong>
</p>

## What's new in 0.4.0

- **Harvesting.** She reaps ripe crops with the engine's own harvest call, tells
  plain from Eternal crops, and replants from her pack ("harvest the carrots",
  "harvest all in the 100x100").
- **One-step installer.** It now downloads `laya.cpp` for you. Pick Vulkan, CUDA 13
  or CUDA 12 and it fetches the engine plus the checkpoint.
- **Smarter gathering.** Hints match whole words against real item names, so "corn"
  finds corn and not a corner; every gathered block gives its true drop; a crop
  reports its real name ("Mature Wheat") instead of an asset key.
- **New HUD icons** for attack, defend, follow, stay, gather, mine, chop, farm and
  downed, all from one source set with consistent centring and weight.
- **Fixes** across the board: planted crops were invisible to the scan on generated
  per-stage block types, "gather everything" was treated as a noun, and the
  interact page had dead space on the right.

## Why is she called Laya?

Laya is named after **[laya.cpp](https://github.com/lkarlslund/laya.cpp)**, the
open-source local inference engine that runs her decision model. The first
version of this companion was built around the idea of putting a real local
model inside Hytale and letting it run on the player's own GPU through Vulkan.

The engine is not an optional add-on. Laya uses the resident `laya.cpp` Vulkan
server for every decision, and the bridge refuses to start without the engine
and its `typed-decisions` checkpoint. The name is the architecture: **a local
brain for a world that usually only has scripted NPCs.**

## Why does she respond so fast?

Laya is designed to feel present, not like a chat window with a three-second
pause. The speed comes from the architecture, not a single trick:

- **The decision engine is resident.** `laya.cpp` starts once and stays warm on
  its own port. A turn does not load a model or restart a process.
- **Deepgram Aura is the default voice.** It sounds the best in testing, and
  the first sentence is synthesized in roughly 1-3 seconds. Edge and Kokoro are
  fallback voice options.
- **Local speech is optional.** Kokoro TTS and Parakeet STT can run on ONNX
  Runtime with no per-turn PyTorch warm-up. Kokoro's ~1 GB of local models are
  not required while Deepgram is the selected engine.
- **Cloud replies stream.** Groq can stream the reply while the first sentence
  is already being turned into speech, so the player hears Laya before the full
  answer has finished generating.
- **The reflex layer is deterministic.** Short commands such as "stay", "follow
  me", and "stop" never wait for an LLM round-trip. The plugin applies the
  action immediately.
- **The game state is local.** The plugin sends a live snapshot of Laya's held
  item, armor, appearance, the real in-game time, and player state over the local
  bridge. There is no remote game server in the conversation path.
- **Localhost only.** The plugin bridge uses `127.0.0.1:8787`; the AI bridge
  uses `127.0.0.1:8788`. No network hop is added beyond the model provider.

On the development machine, typical turns are roughly **0.4-0.8 s for the LLM**
and the first spoken sentence arrives around **1-2 s** after the player sends a
message. Exact numbers depend on the provider, GPU, and whether vision is used.

## What she can do

- Follow you, hold position, defend, attack, and use the custom HUD.
- Talk with a natural voice and a lip/mouth animation that follows speech.
- **Rename her** in Settings: she uses your chosen name in chat, on the overhead
  nameplate, on the HUD card, in her own introduction, and in her system prompt, and
  the name is remembered across restarts even with the bridge closed.
- Remember useful facts and recent conversation.
- Answer truthfully about what she is carrying: her live pack, held item and armor
  are injected into every turn, so she no longer invents weapons she does not have.
- **Pick a personality** in Settings: default, spicy, potty mouth, mean, flirty,
  depressed, military, or unhinged, as a trait layer over her base character.
- Mine and gather with a deterministic task layer and block-name knowledge:
  "gather all that wheat", "gather 5 carrots", "gather the sunflowers", "gather
  everything around us", "gather all the stone in a 50x50".
- **Farm**: till a plot in front of you and plant seeds from her pack or a nearby
  chest: "plant five carrot seeds", "plant any seeds you have", "just seed". See
  the Farming section below. A farming icon appears on the HUD while she works.
- **Harvest**: reap every ripe crop around you with the engine's own harvest call -
  "harvest", "harvest the carrots", "harvest 5", "harvest all in the 100x100", and
  replant from her pack. See the Harvesting section below.
- Use a custom inventory page with Laya's pack, gear slots, player inventory,
  stacking, sorting, and click/double-click/right-click transfers, and a centred
  "Equipped" column.
- **Break the silence on her own**: after two minutes without a message she looks
  around (and sometimes at your screen) and says something about what you are
  doing, spoken aloud with no chat text, then backs off to about five minutes.
- Look at the player's screen when asked to, when screen vision is configured.
- Know the real in-game time and reason about it (no more "before it's dark" at
  midnight).
- **Voice turns are spoken only**: hold the talk key and she answers out loud
  without printing the line to chat; typed chat still shows the text.
- **Talk mode** in Settings switches that key between press-and-hold and a toggle
  (press to start talking, press again to stop).
- Edit her whole character-creator look on the appearance page, with real part
  names and texture previews, applied live and saved for next session.
- Start fresh for each new player: no bundled keys, inventory, memory, or skin.

## Farming

Ask her in plain words and she does the whole job herself: walk to the plot, till
the soil, plant the seed, and tell you what she actually planted (counted from the
block entities, not from what she meant to do).

```
plant five carrot seeds here
plant any seeds you have
just seed
hoe this area and plant cauliflower
plant 10 eternal wheat seeds
```

- **The patch is in front of you**: up to 5 x 5 cells, starting two rows ahead, sized
  to the number of seeds you asked for. `0` / "all" means every seed of that crop she
  can reach.
- **Seeds come from her pack first**, then from any chest within 5 blocks; whatever she
  takes out of a chest she tells you about. If there are none she refuses and says so
  instead of miming the job.
- **Eternal vs normal seeds.** Eternal crops regrow after harvest, normal ones are
  consumed. If she can reach both kinds she asks which ones to plant before starting;
  if only one kind is reachable she uses it silently. Name the kind to skip the question.
- **She needs a hoe.** Tilling spends tool durability, and a worn-out hoe does nothing
  at all, so she checks before she swings, holds the one with the most life left, and
  goes looking in nearby chests for a spare when hers breaks, and tells you when there
  is no spare instead of swinging a dead tool.
- **She walks where the work is.** If the ground stops the engine's straight-line mover
  and she stalls on the spot, she steps beside the cell and carries on rather than
  giving up on it.
- **Say "stop"** at any time to end the task.

Farming is till-and-plant today. The layout that doubles growth rate by putting a
light source in the middle of the plot (Hytale's `LightLevel` growth modifier, x2,
works with a lamp or torch alone) is the next step.

## Harvesting

Ask her to reap and she walks the field crop by crop, using Hytale's own harvest
primitive rather than a break interaction, so a ripe crop gives exactly what the
game would give you, and an Eternal crop is left standing to regrow.

```
harvest
harvest the carrots
harvest 5 carrots
pick whatever's ripe
harvest all in the 100x100
harvest the wheat, don't replant
```

- **Any ripe crop by default**, within 16 blocks of you; name a crop to narrow it.
- **"Nearby" is honest**: an area you name ("in 100x100" = 50 blocks) is taken
  literally, and a bare "harvest" widens only to 32 blocks. She will not walk two
  fields over for one mushroom.
- **Plain crops are consumed; Eternal crops regrow.** She reads which one it was from
  the block itself and says so: "picked 6 carrots, 4 regrow, 1 replanted".
- **Replanting is on by default** from her own pack, topped up from chests within
  5 blocks before she starts. Say "don't replant" / "leave it bare" to stop it.
- **Every count she reports is read back from the world**, never from what she meant
  to do.

## Gathering

Gathering works from the same block knowledge the rest of her tasks use, with two
details worth knowing:

- **Hints are matched as whole words against the game's own item names**, so "corn"
  finds the corn and never the 94 blocks with "corner" in their name.
- **A gathered block gives its real drop.** Harvesting a flower brings back the
  flower; a wild plant gives what the game gives. Nothing is invented.

Say "gather everything around us" for a sweep of trunks, ore, rubble, wild
mushrooms and non-crop plants. It deliberately skips your crops, sown seed blocks,
leaves and bare terrain.

## In-game commands

- `/laya`: the companion's own help, with an appearance-editor link.
- `/laya hud on` / `/laya hud off`: show or hide her HUD card. The choice is
  remembered across restarts until you turn it back on.
- `/laya reset memory`: shows what she remembers (facts, conversation, and the
  recent things she watched you do) and how to clear it.
- `/laya reset memory confirm`: clears all of that memory.

## Voice: Deepgram first

**Deepgram Aura is the first-choice TTS engine.** It sounded the best in
side-by-side testing, so it is the default voice path and the current default
voice is `aura-2-hera-en`.

The project has **$200 of Deepgram starter credits**, which will last a long
time at normal companion use. Deepgram is usage-based after that; you can swap
to the free/local options at any time in Settings.

The other engines are backups:

- **Edge TTS**: free online Microsoft voices, no local models.
- **Kokoro**: local ONNX voice, fully offline once installed.

The roughly **1 GB Kokoro/Parakeet model download is optional**. You only need
it for Kokoro TTS or local microphone speech recognition; Deepgram does not
need those models.

## Install

### Requirements

- Windows 10/11
- Hytale 0.7.x
- Python 3.11
- A GPU laya.cpp can use: Vulkan (NVIDIA, AMD, Intel) or CUDA 12/13 on NVIDIA.
  The installer downloads the matching build and the `typed-decisions` checkpoint.
- Java 25 only if you build the plugin yourself
- **Free Groq API key**: [create one here](https://console.groq.com/keys)
- **Free z.ai API key**: [create one here](https://z.ai/manage-apikey/apikey)
- **NanoGPT API key**: [create one here](https://nano-gpt.com/api) (paid; uncensored primary model)
- **Deepgram API key**: [$200 starter credits here](https://console.deepgram.com/api-keys)
- Optional: about 1 GB for Kokoro TTS and Parakeet local speech recognition

Groq and z.ai are free to start; Deepgram includes $200 of starter credits,
which should last a long time at normal companion use. NanoGPT is optional and
billed per token by NanoGPT.

### Quick start

1. Clone or download this repository.

   ```powershell
   git clone https://github.com/jonferno/LayaHytaleCompanion.git
   cd LayaHytaleCompanion
   ```

2. Run the installer. It downloads the required `laya.cpp` engine for you:

   1. Double-click `install.bat`, or run:

      ```powershell
      powershell -ExecutionPolicy Bypass -File .\install.ps1
      ```

   2. Pick your GPU backend when it asks:

      - **Vulkan** works on NVIDIA, AMD and Intel (about 77 MB)
      - **CUDA 13** is fastest on a current NVIDIA driver (about 263 MB)
      - **CUDA 12** for a V100 or an older NVIDIA driver (about 296 MB)

   3. It fetches the engine plus the `typed-decisions` checkpoint (about 800 MB)
      into `ai-bridge\laya\`, copies the plugin JAR, creates a local config with
      **empty keys**, and installs the Python dependencies.

   Unattended installs can pass the choice and skip prompts:

   ```powershell
   .\install.ps1 -LayaBackend cuda13 -HytalePath "D:\Games\Hytale"
   ```

   The optional local speech models download too unless you pass `-SkipModels`;
   Deepgram works without them. Use `-SkipKokoro` to keep Parakeet STT but skip
   the Kokoro model.

3. Run `start.bat`, open <http://127.0.0.1:8788>, and paste your API keys
   (including Deepgram) into the settings page. Save the settings.
4. Start Hytale. The installer sets the plugin's config paths as user
   environment variables, so restart Hytale once after the first install.
5. In game, talk to Laya in chat or use `/laya`.

See [INSTALL.md](INSTALL.md) for manual setup, the required `laya.cpp` engine,
troubleshooting, and `reset-data.ps1` for a clean inventory, memory, and
appearance.

## How it works

```text
Hytale client
    |
    |  Java plugin (hytale-plugin/)
    |  - custom HUD and inventory page
    |  - NPC movement, tasks, combat, gear
    |  - area/time snapshot for idle banter
    |  - localhost HTTP bridge :8787
    v
Python AI bridge (ai-bridge/)
    - memory and prompt construction
    - NanoGPT (uncensored primary) / Groq / Google / z.ai LLM clients
    - Deepgram Aura TTS (default); Edge + Kokoro backups
    - Parakeet ONNX STT for microphone input
    - idle silence clock, personality layers, vision
    - resident laya.cpp decision server :8090
    v
Hytale world state, speech, actions
```

- `hytale-plugin/`: Java plugin, UI, NPC controller, inventory, combat.
- `ai-bridge/`: Python sidecar: speech, LLM, memory, vision, decisions, idle.
- `ai-bridge/idlelogic.py`: idle-banter silence clock (unit-tested).
- `ai-bridge/gearcheck.py`: gear-question matching for the inventory context.
- `ai-bridge/farming.py`: farm, harvest and gather-request parsing (crop, count, area, seed kind).

- `ai-bridge/personalities.py`: personality trait layers.
- `config/blocks.json`: player words to Hytale block names.
- `config/hytale-knowledge.json`: compact game-knowledge reference.
- `config/laya-persona.txt`: Laya's persona and response contract.
- `dist/LayaPlugin-0.4.0.jar`: convenience plugin build.

## Build the plugin

The repository ships `dist/LayaPlugin-0.4.0.jar`. It is built against the 0.7.x
server API and is **tested on Hytale 0.7.4**. If Hytale changes its API again,
rebuild from source:

```powershell
$env:HYTALE_SERVER_JAR = "D:\Games\Hytale\install\release\package\game\latest\Server\HytaleServer.jar"
cd hytale-plugin
gradle build
```

The Gradle build uses `HYTALE_SERVER_JAR` and optionally `HYTALE_MODS_DIR`; it
does not contain machine-specific paths.

## Settings

The web page at <http://127.0.0.1:8788> has two areas:

- **Settings**: talk and teleport keys, **talk mode** (hold or toggle),
  **companion name**, voice engine and voices, audio devices, LLM models and API
  keys, and the **personality** dropdown (default, spicy, potty mouth, mean,
  flirty, depressed, military, unhinged).
- **Laya Appearance Editor**: her full character-creator look, with real part
  names and texture previews, applied live and saved.

## Next up

- **The light-boosted layout**: Hytale grows crops up to twice as fast when a light
  source sits in the middle of the plot (`LightLevel` growth modifier, x2, satisfied
  by a lamp or torch alone), so the next layout will place the light and ring the
  crops around it.

## Privacy and safety

- No API keys, personal skins, inventories, combat state, or chat memory are
  committed.
- `config/bridge-config.json`, `config/laya-*.json`, `logs/`, `models/`, and
  `ai-bridge/laya/` are git-ignored.
- The bridge listens on localhost only by default.
- API keys are only sent to the provider you configure.
- The default Deepgram voice sends response text to Deepgram's API. Switch to
  Kokoro or Edge in Settings if you want a different voice/privacy tradeoff.
- This repository starts with a fresh history. Do not merge private development
  branches that contain old config or keys.

## Credits

Plugin and bridge code by [Jonferno](https://github.com/jonferno). Built with
Hytale's plugin API, [laya.cpp](https://github.com/lkarlslund/laya.cpp),
[Deepgram Aura](https://deepgram.com/), FastAPI, Kokoro, Parakeet, and the
Hytale modding community. See each upstream project for its license.

### 100% vibe coded

This project was **100% vibe coded** with
[DeepSeek V4.1](https://www.deepseek.com/) and
[OpenCode](https://opencode.ai/). Huge thanks to both, they made building
Laya an absolute joy.

### Inspiration

The original idea came from [chasmlol](https://github.com/chasmlol) and his
Halo CE "Kev" video. Thanks for the spark:
[YouTube - chasm](https://www.youtube.com/@chasmmm).
