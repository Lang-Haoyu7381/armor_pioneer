"""Move the addon's message records from Forge's SimpleChannel onto NeoForge payloads.

Mechanical parts only: imports, the record declaration, the Type/StreamCodec pair, the handler
signature, the side guard and the DistExecutor wrapper. The handful of send sites differ enough
that they are left for a human to edit; the script prints where they are.
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
NET = ROOT / "src" / "main" / "java" / "com" / "ywzjvehicle" / "armor" / "network"

PATHS = {
    "GunnerActionMessage": "gunner_action",
    "GunnerStateMessage": "gunner_state",
    "FordingActionMessage": "fording_action",
    "FordingStateMessage": "fording_state",
    "SuspensionActionMessage": "suspension_action",
    "SuspensionStateMessage": "suspension_state",
    "CrewActionMessage": "crew_action",
    "CrewStateMessage": "crew_state",
    "DoorActionMessage": "door_action",
    "HybridActionMessage": "hybrid_action",
    "HybridStateMessage": "hybrid_state",
}

HEADER = """import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
"""


def convert(path: Path):
    name = path.stem
    text = path.read_text(encoding="utf-8")
    serverbound = "sendToServer" in text

    # --- imports -----------------------------------------------------------------
    text = text.replace("import net.minecraft.network.FriendlyByteBuf;", "")
    text = text.replace("import net.minecraftforge.network.NetworkEvent;", "")
    text = text.replace("import net.minecraftforge.network.PacketDistributor;", "")
    text = text.replace("import java.util.function.Supplier;", "")
    text = text.replace("import net.neoforged.api.distmarker.Dist;", "")
    text = text.replace("import net.neoforged.fml.DistExecutor;", "")
    text = re.sub(r"\n\n\n+", "\n\n", text)

    extra = HEADER
    if serverbound:
        extra += "import net.neoforged.neoforge.network.PacketDistributor;\n"
    if "import com.ywzjvehicle.armor.VehicleArmorMod;" not in text:
        extra += "import com.ywzjvehicle.armor.VehicleArmorMod;\n"
    text = re.sub(r"(package [\w.]+;\n)", r"\1\n" + extra, text, count=1)

    # --- record declaration ------------------------------------------------------
    text = re.sub(r"public record (\w+)\(([\s\S]*?)\)\s*\{",
                  lambda m: f"public record {m.group(1)}({m.group(2)}) implements CustomPacketPayload {{",
                  text, count=1)

    # --- type + codec ------------------------------------------------------------
    block = f"""
    public static final CustomPacketPayload.Type<{name}> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
                    VehicleArmorMod.MOD_ID, "{PATHS[name]}"));

    public static final StreamCodec<RegistryFriendlyByteBuf, {name}> CODEC =
            StreamCodec.of({name}::encode, {name}::decode);

    @Override
    public CustomPacketPayload.Type<{name}> type() {{
        return TYPE;
    }}
"""
    text = re.sub(r"(    public static final int ID = \d+;\n)", r"\1" + block, text, count=1)

    # --- handler -----------------------------------------------------------------
    text = re.sub(r"Supplier<NetworkEvent\.Context> supplier\) \{", "IPayloadContext context) {", text)
    text = text.replace("        NetworkEvent.Context context = supplier.get();\n", "")
    text = text.replace("        context.setPacketHandled(true);\n", "")

    # The old guard asked the packet's direction; a payload knows its own flow.
    text = re.sub(r"if \(!context\.getDirection\(\)\.getReceptionSide\(\)\.isClient\(\)\) \{\n"
                  r"\s*return;\n\s*\}",
                  "if (!context.flow().isClientbound()) {\n            return;\n        }",
                  text)

    # The two client-only state messages deferred the client helper through DistExecutor.
    text = re.sub(r"DistExecutor\.unsafeRunWhenOn\(Dist\.CLIENT,\s*\n?\s*\(\) -> \(\) -> ([\w.]+\(message\))\)",
                  r"\1", text)
    text = re.sub(r"context\.enqueueWork\(\(\) -> (com\.ywzjvehicle\.armor\.client\.[\w.]+\()message\)\);",
                  r"context.enqueueWork(() -> \1message));", text)

    text = re.sub(r"\bFriendlyByteBuf\b", "RegistryFriendlyByteBuf", text)
    text = re.sub(r"\n\n\n+", "\n\n", text)

    path.write_text(text, encoding="utf-8")

    leftovers = [ln.strip() for ln in text.splitlines()
                 if "VehicleArmorNetwork.channel()" in ln or "context.getSender()" in ln
                 or "DistExecutor" in ln]
    if leftovers:
        print(f"{name}: {len(leftovers)} manual site(s)")
        for ln in leftovers:
            print(f"    {ln}")


def main():
    for path in sorted(NET.glob("*Message.java")):
        convert(path)


if __name__ == "__main__":
    main()
