# Security

- API keys belong only in `config/bridge-config.json`, which is git-ignored.
- Never commit `laya-skin.json`, `laya-inventory.json`, `laya-combat.json`,
  `logs/`, or anything under `models/` or `ai-bridge/laya/`.
- The bridge and plugin bind to localhost by default.
- If an API key is ever exposed, revoke it at the provider and create a new one.
  Removing it from files is not enough: it may remain in shell history, backups,
  or old Git objects.
- This repository starts with a fresh history. Do not merge old private
  development branches that contain keys.
