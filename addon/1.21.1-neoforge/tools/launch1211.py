"""Write a Java @argfile that starts the 1.21.1 NeoForge client, for a load test without a launcher.

The launcher's own command line is the version json's, with the placeholders filled in. Writing it
to an argfile rather than to the command line keeps the classpath (144 entries) under the Windows
limit and avoids PowerShell quote mangling.
"""

import json
import os
from pathlib import Path

MC_ROOT = Path(r"E:\.minecraft")
INSTANCE = MC_ROOT / "versions" / "1.21.1-NeoForge_21.1.230"
VERSION = "1.21.1-NeoForge_21.1.230"
OUT = Path(__file__).resolve().parent.parent / "launch1211.args"

vj = json.loads((INSTANCE / f"{VERSION}.json").read_text(encoding="utf-8"))
libs = []
for lib in vj["libraries"]:
    path = (lib.get("downloads") or {}).get("artifact", {}).get("path")
    if not path:
        continue
    full = MC_ROOT / "libraries" / path
    if full.exists():
        libs.append(str(full))

# The version json lists a few libraries twice (gson is the one that bit), and securejarhandler
# rejects a repeated path outright: "Duplicate key .../gson-2.10.1.jar".
classpath = list(dict.fromkeys(libs + [str(INSTANCE / f"{VERSION}.jar")]))
natives = INSTANCE / f"{VERSION}-natives"
assets = MC_ROOT / "assets"
libdir = MC_ROOT / "libraries"

modules = [
    libdir / "cpw/mods/bootstraplauncher/2.0.2/bootstraplauncher-2.0.2.jar",
    libdir / "cpw/mods/securejarhandler/3.0.8/securejarhandler-3.0.8.jar",
    libdir / "org/ow2/asm/asm-commons/9.8/asm-commons-9.8.jar",
    libdir / "org/ow2/asm/asm-util/9.8/asm-util-9.8.jar",
    libdir / "org/ow2/asm/asm-analysis/9.8/asm-analysis-9.8.jar",
    libdir / "org/ow2/asm/asm-tree/9.8/asm-tree-9.8.jar",
    libdir / "org/ow2/asm/asm/9.8/asm-9.8.jar",
    libdir / "net/neoforged/JarJarFileSystems/0.4.1/JarJarFileSystems-0.4.1.jar",
]

args = [
    "-Xmx4G",
    f"-Djava.library.path={natives}",
    f"-Djna.tmpdir={natives}",
    f"-Dorg.lwjgl.system.SharedLibraryExtractPath={natives}",
    f"-Dio.netty.native.workdir={natives}",
    "-Dminecraft.launcher.brand=PCL",
    "-Dminecraft.launcher.version=2",
    # Log every successful mixin application as well as the failures: the point of this launch is
    # to see which of the addon's mixins actually reached their targets.
    "-Dmixin.debug.verbose=true",
    "-cp", ";".join(classpath),
    "-Djava.net.preferIPv6Addresses=system",
    f"-DignoreList=client-extra,{VERSION}.jar",
    f"-DlibraryDirectory={libdir}",
    "-p", ";".join(str(m) for m in modules),
    "--add-modules", "ALL-MODULE-PATH",
    "--add-opens", "java.base/java.util.jar=cpw.mods.securejarhandler",
    "--add-opens", "java.base/java.lang.invoke=cpw.mods.securejarhandler",
    "--add-exports", "java.base/sun.security.util=cpw.mods.securejarhandler",
    "--add-exports", "jdk.naming.dns/com.sun.jndi.dns=java.naming",
    "cpw.mods.bootstraplauncher.BootstrapLauncher",
    "--username", "Dev",
    "--version", VERSION,
    "--gameDir", str(INSTANCE),
    "--assetsDir", str(assets),
    "--assetIndex", "17",
    "--uuid", "00000000000000000000000000000000",
    "--accessToken", "0",
    "--clientId", "0",
    "--xuid", "0",
    "--userType", "legacy",
    "--versionType", "release",
    "--fml.neoForgeVersion", "21.1.230",
    "--fml.fmlVersion", "4.0.42",
    "--fml.mcVersion", "1.21.1",
    "--fml.neoFormVersion", "20240808.144430",
    "--launchTarget", "forgeclient",
]

# QUICKPLAY=<save folder>: go straight into that world, so the vehicle classes load and their
# mixins apply. Left out, the client stops at the title screen.
quick_play = os.environ.get("QUICKPLAY")
if quick_play:
    args += ["--quickPlaySingleplayer", quick_play]

# Argfile rules: a backslash ends a line only inside quotes on Windows; every argument here is a
# simple path or flag, so one per line with no quotes is enough.
OUT.write_text("\n".join(args), encoding="utf-8")
print(f"wrote {OUT} ({len(args)} arguments, classpath {len(classpath)} entries)")
