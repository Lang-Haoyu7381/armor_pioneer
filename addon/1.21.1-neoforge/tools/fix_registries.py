"""Registry, ResourceLocation and event-bus fixes for the 1.21.1 port.

DeferredRegister still exists in NeoForge, but it is created from a vanilla Registry key rather
than a ForgeRegistries constant, and it hands back a DeferredHolder rather than a RegistryObject.
ResourceLocation's constructor is private in 1.21, so every `new` becomes a named factory call.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"


def split_top_level(s):
    out, depth, cur = [], 0, ""
    for ch in s:
        if ch in "([<":
            depth += 1
        elif ch in ")]>":
            depth -= 1
        if ch == "," and depth == 0:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    out.append(cur)
    return out


def rewrite_resource_locations(text):
    marker = "new ResourceLocation("
    while True:
        i = text.find(marker)
        if i < 0:
            return text
        j = i + len(marker)
        depth = 1
        k = j
        while k < len(text) and depth:
            if text[k] == "(":
                depth += 1
            elif text[k] == ")":
                depth -= 1
            k += 1
        args = text[j:k - 1]
        parts = [p.strip() for p in split_top_level(args)]
        if len(parts) == 2:
            repl = f"ResourceLocation.fromNamespaceAndPath({parts[0]}, {parts[1]})"
        else:
            repl = f"ResourceLocation.parse({args.strip()})"
        text = text[:i] + repl + text[k:]


for path in sorted(SRC.rglob("*.java")):
    text = path.read_text(encoding="utf-8")
    before = text

    text = rewrite_resource_locations(text)
    text = text.replace("MinecraftForge.EVENT_BUS", "NeoForge.EVENT_BUS")

    # DeferredRegister's registry argument, and the holder type it returns.
    text = text.replace("DeferredRegister.create(ForgeRegistries.SOUND_EVENTS,",
                        "DeferredRegister.create(Registries.SOUND_EVENT,")
    text = text.replace("DeferredRegister.create(ForgeRegistries.PARTICLE_TYPES,",
                        "DeferredRegister.create(Registries.PARTICLE_TYPE,")
    text = text.replace("DeferredRegister.create(ForgeRegistries.ITEMS,",
                        "DeferredRegister.create(Registries.ITEM,")
    text = re.sub(r"\bRegistryObject<([\w.$]+)>", r"DeferredHolder<\1, \1>", text)
    text = text.replace("import net.neoforged.neoforge.registries.RegistryObject;",
                        "import net.neoforged.neoforge.registries.DeferredHolder;")
    if "Registries." in text and "import net.minecraft.core.registries.Registries;" not in text:
        text = re.sub(r"(package [\w.]+;\n)", r"\1\nimport net.minecraft.core.registries.Registries;\n",
                      text, count=1)

    # Lookup registries: the constants live on BuiltInRegistries now.
    if "ForgeRegistries.ITEMS." in text or "ForgeRegistries.MOB_EFFECTS." in text:
        text = text.replace("ForgeRegistries.ITEMS.", "BuiltInRegistries.ITEM.")
        text = text.replace("ForgeRegistries.MOB_EFFECTS.", "BuiltInRegistries.MOB_EFFECT.")
        text = text.replace("import net.neoforged.neoforge.registries.ForgeRegistries;",
                            "import net.minecraft.core.registries.BuiltInRegistries;")
    text = text.replace("import net.neoforged.neoforge.registries.ForgeRegistries;\n", "")

    if text != before:
        path.write_text(text, encoding="utf-8")
        print(f"patched {path.name}")
