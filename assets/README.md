# Assets

`banner.svg` is the README banner. It is intentionally a source-editable SVG so
you can replace it with your own artwork.

## Replacing the banner

1. Make a banner around **1600 x 360** (or a 4.5:1 ratio).
2. Save it as `assets/banner.png` or `assets/banner.svg`.
3. Update the image path at the top of `README.md`.
4. In GitHub, open **Settings -> Social preview** and upload the same image for
   link previews.

## Screenshots and clips

Add gameplay screenshots here (for example `assets/laya-hud.png`) and reference
them from a `## Media` section in `README.md`.

## HUD icons

`attack.png`, `defend.png`, `follow.png`, `stopfollow.png`, `gather.png`,
`pickaxe.png`, `axe.png`, `hoe.png` and `downed.png` are the HUD task icons
(1024x1024 source).
They are not loaded directly: run `python build_hud_icons.py` from the repo root,
which crops each glyph, fits it to 96 px and writes the 112x112
`@2x` files the HUD loads at 56 px:

| asset | HUD texture |
| --- | --- |
| `attack.png` | `IconAttack@2x.png` |
| `defend.png` | `IconDefend@2x.png` |
| `follow.png` | `IconFollow@2x.png` |
| `stopfollow.png` | `IconStay@2x.png` |
| `gather.png` | `IconGather@2x.png` |
| `pickaxe.png` | `IconMine@2x.png` |
| `axe.png` | `IconChop@2x.png` |
| `hoe.png` | `IconFarm@2x.png` |
| `downed.png` | `IconDowned@2x.png` |

All nine HUD icons are generated from this folder; no hand-drawn icon is left.
