# Laya: a Hytale companion with a brain

**She follows, talks, remembers, farms, harvests, mines and fights beside you.**

Laya is not a scripted follower with a bark file. She is a companion NPC with a local
decision model, a real voice, and a memory of your world. Ask her for something in plain
words and she works out how to do it, then tells you what actually happened.

> "plant five carrot seeds" · "harvest carrots" · "gather mushrooms in a 100x100 area" ·
> "kill that spider" · "defend me" · "hold position" ·
> "follow me" · "stay here" · "what are you carrying?"

---

## What she can do

- **Walk with you**: follow, hold position, come when called, and keep up across terrain.
- **Talk, out loud**: a natural voice with lip movement that follows her speech. Type to
  her, or hold the talk key and just speak.
- **Remember**: facts you tell her, recent conversation, and what she has seen you doing.
- **Farm**: till a plot in front of you and plant seeds from her pack or a nearby chest,
  including Eternal seeds that regrow.
- **Harvest**: reap every ripe crop around you, tell plain crops from Eternal ones, and
  replant from her pack. Every count she reports is read back from the world.
- **Gather and mine**: wood, ore, rubble, crops, flowers, mushrooms. Say "gather
  everything around us" and she sweeps what is actually worth taking.
- **Fight**: defend you, attack what threatens you, and get back up when revived.
- **Look the part**: full character-creator appearance editing, a HUD card, and a custom
  inventory page for her pack and gear.
- **Be someone**: pick a personality, rename her, and she uses that name everywhere.

## Why she feels different

- **The brain is local.** Her decision model runs on your own GPU through `laya.cpp` and
  stays warm between turns, so short commands never wait for a cloud round-trip.
- **She is honest.** When she says she picked six carrots, that came from reading the
  blocks back, not from what she meant to do.
- **She knows the game.** Real item names, real drops, real block data pulled from Hytale's
  own assets, so "corn" means corn and not a corner.
- **Nothing is bundled.** No API keys, no inventory, no save data. Your world stays yours.

---

## Install (about 10 minutes)

1. **Get the companion app.** Download the latest release from
   [GitHub](https://github.com/jonferno/LayaHytaleCompanion/releases) and unzip it. The file
   on the Files tab here is the plugin; the companion app is what makes her think and speak.
2. **Run `install.bat`.** Point it at your Hytale folder (usually `D:\Games\Hytale`). It asks
   once for your GPU backend (Vulkan on NVIDIA, AMD or Intel; CUDA 13 or CUDA 12 on NVIDIA),
   downloads the matching engine and the `typed-decisions` checkpoint (about 800 MB), then
   installs the plugin, the Python dependencies and a config with empty keys.
3. **Start the bridge with `start.bat`.** Open <http://127.0.0.1:8788>, paste your API keys
   (Groq and z.ai are free to start, Deepgram includes $200 of starter credits), and save.
4. **Start Hytale.** Laya spawns beside you. Say **"follow me"** and you are done.

## Requirements

- Windows 10 or 11
- Hytale 0.7.x (built and tested on **0.7.4**)
- Python 3.11
- A GPU laya.cpp can use: Vulkan (NVIDIA, AMD, Intel), or CUDA 12/13 on NVIDIA. The
  installer fetches the engine and checkpoint.
- API keys for the providers you choose; none are included
- Optional: about 1 GB for fully local speech (Kokoro voice, Parakeet recognition)

## Your first five minutes

```
follow me
what are you carrying?
plant five carrot seeds here
harvest the carrots
gather all that wheat
```

## New in 0.4.4

- **She is on the map.** Her marker follows her across chunks, uses her current name, and
  disappears when she is not spawned.
- **One menu for everything.** Interact with her for your pack, her pack, an equipped panel with
  a dedicated weapon slot, a Stack & Sort button in the pack's last cell, and a bottom row of
  Follow, Stay, Attack, Defend, Ranged and Magic buttons.
- **Ranged and magic.** Bows, crossbows and guns fire visible arrows and bolts out to 25
  blocks and spend arrows from her pack; staves and wands cast orbs out to 20 blocks from a
  100-point mana pool, with the weapon's real shoot and cast animations. Out of arrows she
  holds fire and tells you in her own voice.
- **Combat modes and stance abilities.** Melee, ranged and magic are separate weapon families,
  shown on the HUD with a blue mana bar. Attack gets Power Strike (x1.9), Defend gets Shield
  Bash (x1.35), Stay gets Guard Counter (x1.6 after a hit), each on a 4.5 s cooldown.
- **Defend draws the shield.** The shield goes to her offhand next to a one-handed weapon, so
  it is visible instead of hidden behind a two-handed grip.
- **Named targets work at range.** "Kill that rabbit" searches 48 blocks and keeps re-checking
  your crosshair, and she says so when the animal is genuinely not there.
- **Fixes.** Her idle lines are part of the conversation now, so answering one makes sense,
  and if her entity vanishes she respawns beside you within seconds.

## New in 0.4.3

- **Free mode**: "keep mining stone until I say stop" runs until you stop. She mines what
  she can see, clears the grass and dirt above buried ore, steps into the holes she digs
  and searches wider before moving on. Gathering and chopping work the same way.
- **Free farming**: flat 5x5 plots with a two-block gap, ground leveled by breaking grass,
  dirt and stone, every seed kind planted, and new plots tilled once the seeds run out.
- **Any block, any wording**: "free roam mining", "you are free to go mine this" and named
  blocks such as marble or copper share one path.
- **Safer mining** (no teleporting into blocks, unreachable targets skipped) and fixes for
  attack names ("kill that rabbit"), task reports (only the requested block, stone is not
  ore) and stopped-task lines.
- **Auto-update**: the launcher (`start.bat`) and `install.bat` check GitHub and update the
  plugin and bridge files, with a backup of what they replace.

## New in 0.4.2

- **No more corpse farming.** Enemies drop their target the moment she goes down and keep
  ignoring her body until she is back up.
- **Wood orders stay wood.** "Chop this tree" always fells the tree and shows the axe on
  the HUD, however the request is phrased.
- **She reports the haul.** After chopping, mining, gathering, farming or harvesting she
  gives the pack total and a short remark in her current personality, instead of one
  fixed line.
- **Faster turns when a provider is slow**, and a cleaner per-folder config so two
  installs no longer share names or HUD settings.

## Links

- **Source, issues and full install guide:**
  [github.com/jonferno/LayaHytaleCompanion](https://github.com/jonferno/LayaHytaleCompanion)
- **Releases:** [latest](https://github.com/jonferno/LayaHytaleCompanion/releases/latest)
- **Local engine:** [laya.cpp](https://github.com/lkarlslund/laya.cpp)

Built with Hytale's plugin API, laya.cpp, Deepgram Aura, FastAPI, Kokoro and Parakeet.
