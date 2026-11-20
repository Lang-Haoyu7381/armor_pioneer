"""Rewrite the 1.20.1 addon sources onto 1.21.1 NeoForge names.

Two independent passes:

1. SRG member names -> Mojang member names, from srg_to_mojmap.json (built by
   build_srg_mojmap.py). Tokens with no mapping are reported and left alone so javac can
   point at them; they are almost always server-only classes, whose official names are in
   Mojang's server mappings, which this installation does not carry.

2. Forge package roots -> NeoForge package roots. This is a text substitution: the imports
   it produces still have to be checked class by class, and the network package is not
   touched at all because NeoForge deleted SimpleChannel outright.

Usage:  python port_rename.py [--write]
"""

import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
TABLE = Path(__file__).with_name("srg_to_mojmap.json")

SRG = re.compile(r"\b([fmp])_(\d+)_\b")

PACKAGE_MAP = [
    ("net.minecraftforge.api.distmarker", "net.neoforged.api.distmarker"),
    ("net.minecraftforge.eventbus.api", "net.neoforged.bus.api"),
    ("net.minecraftforge.fml.javafmlmod", "net.neoforged.fml.javafmlmod"),
    ("net.minecraftforge.fml.common", "net.neoforged.fml.common"),
    ("net.minecraftforge.fml.loading", "net.neoforged.fml.loading"),
    ("net.minecraftforge.fml.ModList", "net.neoforged.fml.ModList"),
    ("net.minecraftforge.fml.DistExecutor", "net.neoforged.fml.DistExecutor"),
    ("net.minecraftforge.common.MinecraftForge", "net.neoforged.neoforge.common.NeoForge"),
    ("net.minecraftforge.common", "net.neoforged.neoforge.common"),
    ("net.minecraftforge.event.", "net.neoforged.neoforge.event."),
    ("net.minecraftforge.client.event.", "net.neoforged.neoforge.client.event."),
    ("net.minecraftforge.client.gui.overlay.", "net.neoforged.neoforge.client.gui.overlay."),
    ("net.minecraftforge.client.", "net.neoforged.neoforge.client."),
    ("net.minecraftforge.registries.", "net.neoforged.neoforge.registries."),
]


def main():
    write = "--write" in sys.argv
    table = json.loads(TABLE.read_text(encoding="utf-8"))
    unmapped = Counter()
    unmapped_files = defaultdict(set)
    replaced = 0
    files_changed = 0

    for path in sorted(SRC.rglob("*.java")):
        text = path.read_text(encoding="utf-8")
        original = text

        def sub(m):
            nonlocal replaced
            token = m.group(0)
            new = table.get(token)
            if new is None:
                unmapped[token] += 1
                unmapped_files[path.name].add(token)
                return token
            replaced += 1
            return new

        text = SRG.sub(sub, text)
        for old, new in PACKAGE_MAP:
            text = text.replace(old, new)

        if text != original:
            files_changed += 1
            if write:
                path.write_text(text, encoding="utf-8")

    print(f"files changed: {files_changed}")
    print(f"srg tokens replaced: {replaced}")
    print(f"unmapped tokens: {sum(unmapped.values())} occurrences, {len(unmapped)} distinct")
    for token, n in unmapped.most_common(40):
        owners = [f for f, s in unmapped_files.items() if token in s]
        print(f"  {token:14} x{n:<4} {', '.join(owners[:3])}")
    if not write:
        print("(dry run - pass --write to apply)")


if __name__ == "__main__":
    sys.exit(main())
