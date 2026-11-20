"""Build an SRG-name -> Mojang-name table for 1.20.1.

The addon source was written against the SRG runtime jar (class names are Mojang's, member
names are SRG). 1.21.1 NeoForge runs on Mojang names throughout, so every f_/m_ token has to
move. The bridge is the obfuscated name, which exists in both mapping files of the same
version:

  joined.tsrg                     obf -> SRG     (MCP config, 1.20.1)
  client-1.20.1-...-mappings.txt  obf -> Mojang  (Mojang official, 1.20.1)

Fields are disambiguated by their owning class (a class cannot hold two fields of one name).
Methods additionally need a descriptor, because obfuscation collapses overloads onto one
letter. SRG ids are globally unique, so the final table is a flat name -> name dict.
"""

import json
import re
import sys
import zipfile
from pathlib import Path

MCP_ZIP = Path(r"E:\.minecraft\libraries\de\oceanlabs\mcp\mcp_config\1.20.1-20230612.114412"
               r"\mcp_config-1.20.1-20230612.114412.zip")
OFFICIAL = Path(r"E:\.minecraft\libraries\net\minecraft\client\1.20.1-20230612.114412"
                r"\client-1.20.1-20230612.114412-mappings.txt")

PRIM = {"boolean": "Z", "byte": "B", "char": "C", "short": "S", "int": "I",
        "long": "J", "float": "F", "double": "D", "void": "V"}


def java_type_to_desc(t):
    t = t.strip()
    if t.endswith("[]"):
        return "[" + java_type_to_desc(t[:-2])
    if t in PRIM:
        return PRIM[t]
    return "L" + t.replace(".", "/") + ";"


def official_sig_to_desc(sig):
    """'27:30:net.minecraft.world.phys.Vec3 fromRGB24(int)' -> '(I)Lnet/minecraft/...;'"""
    sig = re.sub(r"^\d+:\d+:", "", sig)
    # strip generics if any slipped through
    sig = re.sub(r"<[^<>]*>", "", sig)
    m = re.match(r"^(.+?)\s+(<init>|[A-Za-z_$][\w$]*)\((.*)\)$", sig)
    if not m:
        return None
    ret, _name, params = m.groups()
    params = "" if params.strip() == "" else params
    parts = [] if not params else split_params(params)
    return "(" + "".join(java_type_to_desc(p) for p in parts) + ")" + java_type_to_desc(ret)


def split_params(s):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch == "<":
            depth += 1
        elif ch == ">":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    if cur:
        out.append(cur)
    return out


def parse_tsrg(text):
    """Return (obf_srg, obf_moj_key -> srg) where key is (class, name, desc|None)."""
    obf_srg = {}
    members = {}
    cur = None
    for line in text.splitlines():
        if not line.strip():
            continue
        if not line.startswith("\t"):
            parts = line.split()
            if parts[0] == "tsrg2":
                continue
            if len(parts) >= 2:
                cur = parts[0]
                obf_srg[cur] = parts[1]
            continue
        parts = line.strip().split()
        if len(parts) < 2:
            continue
        name, srg = parts[0], parts[1]
        if len(parts) >= 3 and parts[1].startswith("("):
            # method: name desc srg id
            desc, srg = parts[1], parts[2]
            members[(cur, name, desc)] = srg
        else:
            members[(cur, name, None)] = srg
    return obf_srg, members


def parse_official(path):
    """Return obf_class -> moj_class and (obf_class, obf_member, desc|None) -> moj_member."""
    classes = {}
    members = {}
    cur_obf = None
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        if not line.startswith(" "):
            m = re.match(r"^(\S+) -> (\S+):$", line)
            if m:
                cur_obf = m.group(2)
                classes[cur_obf] = m.group(1)
            else:
                cur_obf = None
            continue
        if cur_obf is None:
            continue
        body = line.strip()
        if " -> " not in body:
            continue
        left, obf = body.rsplit(" -> ", 1)
        if re.match(r"^\d+:\d+:", left):
            desc = official_sig_to_desc(left)
            if desc:
                mm = re.match(r"^(.+?)\s+([A-Za-z_$][\w$]*)\((.*)\)$",
                              re.sub(r"^\d+:\d+:", "", left))
                if mm:
                    members[(cur_obf, obf, desc)] = mm.group(2)
        else:
            # field: "type name"
            mm = re.match(r"^(.+?)\s+([A-Za-z_$][\w$]*)$", left)
            if mm:
                members[(cur_obf, obf, None)] = mm.group(2)
    return classes, members


def moj_desc_to_obf(desc, moj2obf):
    """Descriptors carry class names, and the two sides spell them differently."""
    return re.sub(r"L([^;]+);",
                  lambda m: "L" + moj2obf.get(m.group(1).replace("/", "."), m.group(1)) + ";",
                  desc)


def main():
    with zipfile.ZipFile(MCP_ZIP) as z:
        tsrg = z.read("config/joined.tsrg").decode("utf-8", "replace")
    obf_srg, srg_members = parse_tsrg(tsrg)
    obf_moj, moj_members = parse_official(OFFICIAL)
    moj2obf = {v: k for k, v in obf_moj.items()}

    # Re-key the official descriptors onto obfuscated class names so they line up with tsrg.
    rekeyed = {}
    for (cls, name, desc), moj in moj_members.items():
        if desc is not None:
            desc = moj_desc_to_obf(desc, moj2obf)
        rekeyed[(cls, name, desc)] = moj
    moj_members = rekeyed

    table = {}
    missing = 0
    for (cls, name, desc), srg in srg_members.items():
        key = (cls, name, desc)
        moj = moj_members.get(key)
        if moj is None:
            missing += 1
            continue
        table[srg] = moj

    out = Path(__file__).with_name("srg_to_mojmap.json")
    out.write_text(json.dumps(table, indent=0, sort_keys=True), encoding="utf-8")
    print(f"classes obf->srg {len(obf_srg)}, obf->moj {len(obf_moj)}")
    print(f"members srg {len(srg_members)}, moj {len(moj_members)}")
    print(f"table {len(table)} entries, {missing} unmatched")
    for probe in ("f_82479_", "m_7096_", "f_82481_", "m_20185_"):
        print(f"  {probe} -> {table.get(probe)}")


if __name__ == "__main__":
    sys.exit(main())
