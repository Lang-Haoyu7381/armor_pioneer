"""Check every mixin's targets against the 1.21.1 jars.

javac checks the body of an injected method. It cannot check the annotations that decide whether
that body is injected at all: @Shadow, @Inject(method=...), @Accessor, and the @At targets are
strings resolved by Mixin while the game starts. A wrong one is not a compile error and not a
failing build - it is a crash report before the main menu, or (with a @Shadow) "field x was not
found in target".

This walks the sources and asks javap about each name. Run it after any change to the mixins or
to the base mod jar.
"""

import os
import re
import subprocess
import sys
from functools import lru_cache
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIXINS = ROOT / "src" / "main" / "java" / "com" / "ywzjvehicle" / "armor" / "mixin"

MC = r"E:\.minecraft\libraries\net\minecraft\client\1.21.1-20240808.144430\client-1.21.1-20240808.144430-srg.jar"
# The base mod jar the launcher actually hands NeoForge, resolved the same way build1211.ps1
# resolves it. A hard-coded version here silently audits the wrong jar after an update.
BASE_DIR = Path(r"E:\.minecraft\versions\1.21.1-NeoForge_21.1.230\mods")
_base_jars = [p for p in BASE_DIR.glob("ywzj_vehicle-1.21.1-*.jar")
              if not p.name.startswith("ywzj_vehicle_armor")]
BASE = os.environ.get("YWZJ_JAR") or (
    str(max(_base_jars, key=lambda p: p.stat().st_mtime)) if _base_jars else
    str(BASE_DIR / "ywzj_vehicle-1.21.1-0.6.3.jar"))
NEO = (r"E:\.minecraft\libraries\net\neoforged\neoforge\21.1.230\neoforge-21.1.230-universal.jar;"
       r"E:\.minecraft\libraries\net\neoforged\neoforge\21.1.230\neoforge-21.1.230-client.jar")
CP = ";".join([MC, BASE, NEO])


@lru_cache(maxsize=None)
def members(fqcn):
    """{name: set(desc)} for fields and methods, plus the raw set of field names."""
    try:
        out = subprocess.run(
            ["javap", "-J-Duser.language=en", "-s", "-p", "-classpath", CP, fqcn],
            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
    except Exception as e:  # noqa: BLE001
        return None, str(e)
    if out.returncode != 0:
        return None, (out.stderr or out.stdout).strip().splitlines()[:1]
    methods, fields = {}, set()
    lines = out.stdout.splitlines()
    supers = []
    header = re.search(r"^(?:\S+ )*(?:class|interface|enum) ([\w.$]+)(.*)$", out.stdout, re.M)
    if header:
        rest = header.group(2)
        ext = re.search(r"extends\s+([\w.$, ]+?)(?:\s+implements|\s*\{|$)", rest)
        impl = re.search(r"implements\s+([\w.$, ]+?)(?:\s*\{|$)", rest)
        for part in (ext, impl):
            if part:
                supers.extend(x.strip() for x in part.group(1).split(",") if x.strip())
        supers = [s for s in supers if s != "java.lang.Object"]
    for i, line in enumerate(lines):
        m = re.match(r"^\s{2}.*?([A-Za-z_$][\w$]*)\((.*?)\);\s*$", line)
        if m:
            desc = ""
            if i + 1 < len(lines) and "descriptor:" in lines[i + 1]:
                desc = lines[i + 1].split("descriptor:", 1)[1].strip()
            methods.setdefault(m.group(1), set()).add(desc)
            continue
        f = re.match(r"^\s{2}.*?([A-Za-z_$][\w$]*);\s*$", line)
        if f:
            fields.add(f.group(1))
    return (methods, fields, tuple(supers)), None


def has_member(fqcn, name, desc=None):
    """Look through the superclass chain: a call site may name the subclass while the body is
    inherited, which is exactly the shape both of the 1.21.1 failures took."""
    seen = set()
    todo = [fqcn]
    while todo:
        current = todo.pop()
        if current in seen:
            continue
        seen.add(current)
        info, _ = members(current)
        if info is None:
            continue
        methods, fields, supers = info
        if name in fields:
            return True
        if name in methods:
            # A descriptor from an @At target is the full "(params)return"; a short one is the
            # "(params)" prefix before the return type, which is what the regex captures.
            if desc is None or any(d.startswith(desc) for d in methods[name]):
                return True
        todo.extend(supers)
    return False


def resolve(imports, simple, pkg):
    if simple in imports:
        return imports[simple]
    if "." in simple:
        return simple
    return f"{pkg}.{simple}"


def strip_generics(s):
    return re.sub(r"<[^<>]*>", "", s)


def main():
    failures = 0
    checked = 0
    files = sorted(MIXINS.glob("*.java"))
    pkg = "com.ywzjvehicle.armor.mixin"

    for path in files:
        text = path.read_text(encoding="utf-8")
        imports = {name: f"{owner}.{name}"
                   for owner, name in re.findall(r"^import\s+([\w.]+)\.(\w+);", text, re.M)}
        target = re.search(r"@Mixin\((\w+)\.class\)", text)
        if not target:
            continue
        tname = resolve(imports, target.group(1), pkg)
        info, err = members(tname)
        checked += 1
        if info is None:
            print(f"FAIL {path.name}: target {tname} not found {err}")
            failures += 1
            continue
        methods, fields, _supers = info

        def bad(msg):
            nonlocal failures
            failures += 1
            print(f"FAIL {path.name}: {msg}")

        # @Shadow, followed by a field or method declaration on a later line
        for m in re.finditer(r"@Shadow\s*(?:@\w+\s*)*\n\s*(?:private|protected|public|final|static|\s)*"
                             r"[\w.$<>\[\]]+\s+([A-Za-z_$][\w$]*)\s*(?=[;=(])", text):
            name = m.group(1)
            if name in ("static",):
                continue
            # @Shadow does NOT resolve inherited members - Mixin looks only at the target class.
            # So this check deliberately does not walk supers, unlike the call-site checks below.
            if name in fields or name in methods:
                continue
            bad(f"@Shadow {name} not a field or method in {tname}")

        # @Accessor / @Invoker
        for m in re.finditer(r"@(Accessor|Invoker)\(\"([^\"]+)\"\)", text):
            name = m.group(2)
            if name not in fields and name not in methods:
                bad(f"@{m.group(1)}(\"{name}\") not in {tname}")

        # @Inject / @Redirect / @ModifyVariable method = "..."
        for m in re.finditer(r"method\s*=\s*\"([^\"]+)\"", text):
            sig = m.group(1)
            name = sig.split("(", 1)[0]
            desc = "(" + sig.split("(", 1)[1] if "(" in sig and sig.endswith(")") else None
            if not has_member(tname, name, desc):
                bad(f"method target {sig} not in {tname} or its supers")

        # @At(target = "Lowner;name(...)ret")
        for m in re.finditer(r"target\s*=\s*\"L([\w/$]+);([\w$<>]+)(\([^\"]*)?\"", text):
            owner, name, desc = m.group(1), m.group(2), m.group(3)
            owner = owner.replace("/", ".")
            if owner.startswith("net.minecraft.") or owner.startswith("org.ywzj."):
                info2, err2 = members(owner)
                if info2 is None:
                    bad(f"@At owner {owner} not found")
                    continue
                # An invocation whose owner is a subclass and whose body is inherited is legal
                # bytecode, so walk the chain here too.
                if not has_member(owner, name, desc):
                    bad(f"@At target {owner}.{name}{desc or ''} not found")

    print(f"\nchecked {checked} mixin target class(es), {failures} problem(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
