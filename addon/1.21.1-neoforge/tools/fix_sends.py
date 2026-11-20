"""Replace the SimpleChannel send sites with NeoForge's PacketDistributor helpers."""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"

PLAYER = re.compile(r"VehicleArmorNetwork\.channel\(\)\.send\(\s*PacketDistributor\.PLAYER\.with\(\(\) -> (\w+)\),")
TRACKING = re.compile(r"VehicleArmorNetwork\.channel\(\)\.send\(\s*"
                      r"PacketDistributor\.TRACKING_ENTITY\.with\(\(\) -> (\w+)\),")
TO_SERVER = re.compile(r"VehicleArmorNetwork\.channel\(\)\.sendToServer\(")

for path in sorted(SRC.rglob("*.java")):
    text = path.read_text(encoding="utf-8")
    before = text

    text = TRACKING.sub(lambda m: f"PacketDistributor.sendToPlayersTrackingEntity({m.group(1)},", text)
    text = PLAYER.sub(lambda m: f"PacketDistributor.sendToPlayer({m.group(1)},", text)
    text = TO_SERVER.sub("PacketDistributor.sendToServer(", text)

    text = text.replace("        ServerPlayer sender = context.getSender();\n",
                        "        if (!(context.player() instanceof ServerPlayer sender)) {\n"
                        "            return;\n        }\n")

    if text != before:
        path.write_text(text, encoding="utf-8")
        print(f"patched {path.name}")
