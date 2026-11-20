"""Second round of API fixes: codecs, packet flow, imports, registry generics."""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src" / "main" / "java"
NET = SRC / "com" / "ywzjvehicle" / "armor" / "network"

# --- 1. CustomPacketPayload.codec takes the member encoder (value, buffer); StreamCodec.of
#        takes (buffer, value), which is the opposite order from the encode methods here.
for path in NET.glob("*Message.java"):
    text = path.read_text(encoding="utf-8")
    text = re.sub(r"StreamCodec\.of\((\w+)::encode, \1::decode\)",
                  r"CustomPacketPayload.codec(\1::encode, \1::decode)", text)
    text = text.replace("context.flow().isClientbound()",
                        "context.flow() != PacketFlow.CLIENTBOUND")
    if "PacketFlow.CLIENTBOUND" in text and "import net.minecraft.network.protocol.PacketFlow;" not in text:
        text = text.replace("import net.neoforged.neoforge.network.handling.IPayloadContext;",
                            "import net.minecraft.network.protocol.PacketFlow;\n"
                            "import net.neoforged.neoforge.network.handling.IPayloadContext;")
    path.write_text(text, encoding="utf-8")

# --- 2. ClientTickEvent lives on the client package, not the common tick package.
for path in SRC.rglob("*.java"):
    text = path.read_text(encoding="utf-8")
    before = text
    text = text.replace("import net.neoforged.neoforge.event.tick.ClientTickEvent;",
                        "import net.neoforged.neoforge.client.event.ClientTickEvent;")
    if "PacketDistributor." in text and "import net.neoforged.neoforge.network.PacketDistributor;" not in text:
        text = re.sub(r"(package [\w.]+;\n)", r"\1\nimport net.neoforged.neoforge.network.PacketDistributor;\n",
                      text, count=1)
    if text != before:
        path.write_text(text, encoding="utf-8")
        print(f"patched {path.name}")

# --- 3. The legacy crew message has no server work left; its old one-liner referenced SimpleChannel.
p = NET / "CrewActionMessage.java"
text = p.read_text(encoding="utf-8")
text = text.replace("        supplier.get().setPacketHandled(true);\n", "")
p.write_text(text, encoding="utf-8")

# --- 4. DeferredRegister<ParticleType<?>> yields DeferredHolder<ParticleType<?>, SimpleParticleType>.
p = SRC / "com" / "ywzjvehicle" / "armor" / "ArmorParticles.java"
text = p.read_text(encoding="utf-8")
text = text.replace("DeferredHolder<SimpleParticleType, SimpleParticleType>",
                    "DeferredHolder<ParticleType<?>, SimpleParticleType>")
p.write_text(text, encoding="utf-8")

# --- 5. Tab contents take the item, not the holder.
p = SRC / "com" / "ywzjvehicle" / "armor" / "VehicleArmorItems.java"
text = p.read_text(encoding="utf-8")
text = text.replace("event.accept(ARMOR_SCANNER);", "event.accept(ARMOR_SCANNER.get());")
text = text.replace("event.accept(AMMO_COMPENDIUM);", "event.accept(AMMO_COMPENDIUM.get());")
p.write_text(text, encoding="utf-8")

# --- 6. 1.21 removed Registry.getValue and MobEffectInstance(MobEffect, ...); both are holders now.
p = SRC / "com" / "ywzjvehicle" / "armor" / "ArmorSystem.java"
text = p.read_text(encoding="utf-8")
text = text.replace(
    'MobEffect blindness = BuiltInRegistries.MOB_EFFECT.getValue(ResourceLocation.parse("blindness"));',
    'Holder<MobEffect> blindness = BuiltInRegistries.MOB_EFFECT\n'
    '                .getHolder(ResourceLocation.parse("blindness")).orElse(null);')
p.write_text(text, encoding="utf-8")

# --- 7. Screen.mouseScrolled gained a horizontal axis in 1.21.
for name in ("LoadChooserScreen.java", "AmmoCompendiumScreen.java"):
    p = SRC / "com" / "ywzjvehicle" / "armor" / name
    text = p.read_text(encoding="utf-8")
    text = text.replace("mouseScrolled(double mouseX, double mouseY, double scrollDelta)",
                        "mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollDelta)")
    text = text.replace("super.mouseScrolled(mouseX, mouseY, scrollDelta)",
                        "super.mouseScrolled(mouseX, mouseY, scrollX, scrollDelta)")
    p.write_text(text, encoding="utf-8")

# --- 8. RenderGuiEvent no longer carries the window, and its partial tick is a DeltaTracker.
p = SRC / "com" / "ywzjvehicle" / "armor" / "client" / "ThermalSmokeOverlay.java"
text = p.read_text(encoding="utf-8")
text = text.replace("int windowWidth = event.getWindow().getGuiScaledWidth();",
                    "int windowWidth = minecraft.getWindow().getGuiScaledWidth();")
text = text.replace("int windowHeight = event.getWindow().getGuiScaledHeight();",
                    "int windowHeight = minecraft.getWindow().getGuiScaledHeight();")
text = text.replace("float partialTick = event.getPartialTick();",
                    "float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);")
p.write_text(text, encoding="utf-8")

p = SRC / "com" / "ywzjvehicle" / "armor" / "client" / "NightVisionHandler.java"
text = p.read_text(encoding="utf-8")
text = text.replace("chain.process(event.getPartialTick());",
                    "chain.process(event.getPartialTick().getGameTimeDeltaPartialTick(false));")
p.write_text(text, encoding="utf-8")
print("done")
