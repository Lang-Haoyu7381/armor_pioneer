"""Move the 1.20.1 phase-based TickEvent handlers onto NeoForge's Post events.

1.21 split every tick event into Pre and Post subclasses, so `event.phase != Phase.END` has no
field left to read. A Post handler is the END phase.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java" / "com" / "ywzjvehicle" / "armor"

KINDS = {
    "CrewState.java": ("ServerTickEvent", "Server"),
    "GunnerSessionHandler.java": ("PlayerTickEvent", "Player"),
    "ProjectileCrack.java": ("ClientTickEvent", "Client"),
    "WeaponEffects.java": ("ClientTickEvent", "Client"),
    "WeaponKeyHandler.java": ("ClientTickEvent", "Client"),
    "NightVisionHandler.java": ("ClientTickEvent", "Client"),
    "PeriscopeView.java": ("ClientTickEvent", "Client"),
}

for name, (event, _side) in KINDS.items():
    path = next(SRC.rglob(name))
    text = path.read_text(encoding="utf-8")
    text = text.replace("import net.neoforged.neoforge.event.TickEvent;",
                        f"import net.neoforged.neoforge.event.tick.{event};")
    text = re.sub(rf"TickEvent\.{event} event", f"{event}.Post event", text)

    # Drop the END-phase guard; a Post handler runs at the end by construction.
    text = re.sub(r"\n\s*if \(event\.phase != TickEvent\.Phase\.END\) \{\n\s*return;\n\s*\}", "", text)
    text = re.sub(r"\n\s*if \(event\.phase != TickEvent\.Phase\.END\n\s*(\|\| [^)]*)\) \{\n(\s*)return;\n\s*\}",
                  r"\n\1", text)

    # PlayerTickEvent extends PlayerEvent in 1.21; the .player field is gone.
    text = text.replace("event.player instanceof ServerPlayer player",
                        "event.getEntity() instanceof ServerPlayer player")

    path.write_text(text, encoding="utf-8")
    print(f"patched {name}")
