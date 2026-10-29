# Combat abilities for every weapon style

0.4.4 shipped the frame this plugs into: modes (melee / ranged / magic), one ability slot per
stance, cooldowns, mana, ammo, visible projectiles, and the item's own animation names. What
is missing is a distinct ability per weapon class, so a dagger plays differently from a
battleaxe.

## How an ability runs today

- `CombatController.swingTick` lands the hit; one stance ability may replace it on a cooldown
  (`Power Strike`, `Shield Bash`, `Guard Counter`).
- `rangedTick` / `magicTick` fire the shot or cast, spend ammo or mana, play the item animation
  and queue a visible projectile through `ProjectileLaunchSystem`.
- Animations come from the held item's `ItemPlayerAnimations` set, never a guessed id.
- Damage is applied by the plugin (`DamageSystems.executeDamage`); the engine's projectile
  impact only damages for a player shooter, so projectiles are visual.

## Per weapon style

| Style | Ability | Trigger | Effect | Cost | Status |
|---|---|---|---|---|---|
| Sword | Riposte | Defend, after a block | x1.5 counter, brief stagger | 4.5 s | planned |
| Longsword (2H) | Heavy Slash | Attack | x1.7 in a 120° arc, slower | 6 s | planned |
| Axe | Cleave | Attack | x1.3 to up to 3 targets in a 90° arc | 5 s | planned |
| Battleaxe (2H) | Whirlwind | Attack | x1.2 to every target within 3.5 blocks | 7 s | planned |
| Mace / Club | Crushing Blow | Any melee stance | x1.4 and a stagger (slow the target) | 6 s | planned |
| Dagger | Quick Stab | Attack | two hits of x0.8 at 0.3 s apart | 3 s | planned |
| Spear | Thrust | Attack | x1.2 at 4.2 blocks (longer reach) | 4 s | planned |
| Spear | Throw | Ranged slot | physical projectile, drops the spear | 1 spear | planned |
| Shield | Bash | Defend | x1.35 and stagger (shipped) | 4.5 s | done |
| Shield | Block stance | Defend | damage reduction while planted | passive | planned |
| Unarmed | Jab | Attack | x1.1, fast | 2 s | planned |
| Bow | Volley | Ranged | 3 arrows over 0.4 s, 1 each | 3 arrows | planned |
| Bow | Charged Shot | Ranged, held | x2 arrow, slower flight | 1 arrow, 6 s | planned |
| Crossbow | Bolt | Ranged | x1.6 bolt, then a reload pause | 1 bolt, 2 s reload | planned |
| Gun | Shot | Ranged | x1.5, loud | 1 bullet | gated on ammo |
| Staff | Magic Orb | Magic | 5 + level, 25 mana (shipped) | 25 mana, 2.4 s | done |
| Staff (frost) | Ice Bolt | Magic | Orb + Freeze 2 s on hit | 30 mana, 3.5 s | planned |
| Staff (flame) | Fire Bolt | Magic | Orb + burn | 30 mana, 3.5 s | planned |
| Wand | Quick Bolt | Magic | 0.7x orb, faster cadence | 15 mana, 1.4 s | planned |

Stance hooks that stay as they are: `attack` hunts, `defend` holds near the player with a
shield, `stay` guards its anchor, `follow` only fights reactively.

## Engine notes learned in 0.4.4

- The engine refuses a direct utility -> hotbar item swap; go through storage.
- The client draws the offhand from `Utility.getActiveItem()`, but only after
  `setOutdatedEquipment(true)`; selecting the slot alone pushes nothing.
- A two-handed grip (mace, battleaxe, longsword) hides the offhand render entirely.
- NPCEntity models expose only movement/state animation sets; attack animations come from the
  held item's `ItemPlayerAnimations`.
- Projectiles need a `CommandBuffer`, available only inside an ECS system.

## Order of work

1. Per-class melee abilities behind one table (weapon class -> ability, multiplier, cooldown).
   Start with sword, axe, mace, dagger, spear; keep the current stance abilities as defaults.
2. Shield block: a passive damage reduction while defending with a shield out.
3. Ranged variants: bow volley, charged shot, crossbow reload.
4. Magic variants: frost and flame staves with effects; wand cadence.
5. Stagger support: a short movement/attack slow on the target, used by Crushing Blow, Bash and
   Heavy Slash.
6. Ability UI: a second row on the interact page showing the current weapon's ability and its
   cooldown, driven by the same table.
