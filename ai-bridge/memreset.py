def counts(memory: dict) -> tuple:
    """(facts, turns, events) counts - the same read the clear uses."""
    m = memory or {}
    return (len(m.get("facts") or []), len(m.get("chat") or []), len(m.get("events") or []))


def clear(memory: dict) -> None:
    """Empty the memory lists in place, keeping any other keys."""
    if memory is None:
        return
    memory["facts"] = []
    memory["chat"] = []
    memory["events"] = []
