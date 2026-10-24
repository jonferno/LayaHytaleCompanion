"""Task 2: the idle-banter silence clock, split out of main.py so it is unit-testable.

Importing `main` boots FastAPI/sounddevice, so this pure timing logic lives on
its own (mirrors gearcheck.py). The clock works on epoch milliseconds: the
first unprompted line keys off the gap since the player's last real message,
and every repeat keys off the gap since the LATER of the last fire and the
player's last message (so a player message re-arms the cadence rather than
blocking it).
"""


def idle_gap_ms(now_ms: int, last_player_ms: int) -> int:
    """Milliseconds since the last real player message (>=0)."""
    return max(0, int(now_ms) - int(last_player_ms))


def should_fire(now_ms: int, last_player_ms: int, last_fire_ms: int,
                first_ms: int, repeat_ms: int) -> bool:
    """True when an idle turn is due.

    Before any fire (last_fire_ms == 0) the gap since the player's last message
    must reach first_ms. After a fire, the gap since the LATER of the last fire
    and the last player message must reach repeat_ms, so the player speaking
    re-arms the cadence instead of blocking it forever.
    """
    if now_ms - last_player_ms < first_ms:
        return False
    if last_fire_ms == 0:
        return True
    # After a fire, the gap must reach repeat_ms measured from the LATER of the
    # last fire and the last player message, so the player speaking re-arms the
    # cadence instead of blocking it forever.
    since = max(last_fire_ms, last_player_ms)
    return (now_ms - since) >= repeat_ms
