# Runtime configuration

These files are safe to commit:

- `blocks.json` - word-to-block mapping used by mining/gathering
- `hytale-knowledge.json` - small game-knowledge reference
- `laya-persona.txt` - Laya's system prompt
- `bridge-config.example.json` - template with empty API keys

These files are **personal and git-ignored**:

- `bridge-config.json` - your API keys and local settings
- `laya-skin.json` - Laya's appearance
- `laya-inventory.json` - Laya's inventory
- `laya-combat.json` - health/downed state

A new install starts without the personal files, so Laya starts with a clean
inventory, no memory, and a default appearance. Delete them while Hytale is
closed to reset an existing install.
