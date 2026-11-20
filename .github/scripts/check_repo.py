# -*- coding: utf-8 -*-
"""Repository sanity checks that do not need Minecraft.

Run from the repository root:  python .github/scripts/check_repo.py
Exits non-zero on the first category of problems.
"""
import json
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
errors = []
notes = []


def tracked_files():
    out = subprocess.run(
        ["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True
    ).stdout
    return [p for p in out.decode("utf-8").split("\0") if p]


def strip_line_comments(text: str) -> str:
    """Remove // comments outside of strings (the animation controllers use them)."""
    out = []
    i, n = 0, len(text)
    in_str = esc = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if esc:
                esc = False
            elif c == "\\":
                esc = True
            elif c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            while i < n and text[i] not in "\r\n":
                i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


# 1. no build artifacts or editor backups are tracked -------------------------
for f in tracked_files():
    p = Path(f)
    if ".bak" in p.name or "__pycache__" in p.parts or p.suffix == ".pyc":
        errors.append(f"tracked backup / artifact: {f}")
    if p.suffix == ".log":
        errors.append(f"tracked log file: {f}")

# 2. the pack manifest keeps its file-format invariants ----------------------
meta_path = ROOT / "pack" / "armor_pioneer" / "vehicle_pack.meta.json"
if not meta_path.exists():
    errors.append("missing pack/armor_pioneer/vehicle_pack.meta.json")
else:
    raw = meta_path.read_bytes()
    if raw.startswith(b"\xef\xbb\xbf"):
        errors.append("vehicle_pack.meta.json has a UTF-8 BOM")
    if b"\r" in raw or b"\n" in raw:
        errors.append("vehicle_pack.meta.json must be a single line")
    try:
        meta = json.loads(raw.decode("utf-8"))
        for key in ("namespace", "title", "version", "dependencies"):
            if key not in meta:
                errors.append(f"vehicle_pack.meta.json is missing '{key}'")
        if meta.get("namespace") != "armor_pioneer":
            errors.append("vehicle_pack.meta.json namespace is not armor_pioneer")
    except Exception as exc:  # noqa: BLE001
        errors.append(f"vehicle_pack.meta.json does not parse: {exc}")

# 3. every JSON in the pack parses (allowing // comments) --------------------
json_files = sorted((ROOT / "pack").rglob("*.json"))
for p in json_files:
    try:
        data = strip_line_comments(p.read_text(encoding="utf-8"))
        json.loads(data)
    except Exception as exc:  # noqa: BLE001
        errors.append(f"{p.relative_to(ROOT)} does not parse: {exc}")

notes.append(f"checked {len(json_files)} pack JSON file(s)")

# 4. the release zip carries the manifest at its root -----------------------
import zipfile  # noqa: E402

zip_path = ROOT / "releases" / "armor_pioneer-1.1.7.zip"
if zip_path.exists():
    with zipfile.ZipFile(zip_path) as z:
        names = set(z.namelist())
    for required in ("vehicle_pack.meta.json", "pack.png"):
        if required not in names:
            errors.append(f"{zip_path.name}: missing {required} at the archive root")

for n in notes:
    print("note:", n)
if errors:
    print()
    for e in errors:
        print("ERROR:", e)
    sys.exit(1)
print("OK")
