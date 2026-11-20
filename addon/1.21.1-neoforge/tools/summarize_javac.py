"""Condense a javac log into one line per error: file:line message | symbol | location."""

import re
import sys
from pathlib import Path

log = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
lines = log.splitlines()
head = re.compile(r"^(?P<file>[^:\r\n]+\.java):(?P<line>\d+): error: (?P<msg>.*)$")

errors = []
i = 0
while i < len(lines):
    m = head.match(lines[i].strip())
    if not m:
        i += 1
        continue
    block = [lines[i + k].strip() for k in range(1, 6) if i + k < len(lines)]
    symbol = next((b[len("symbol:"):].strip() for b in block if b.startswith("symbol:")), "")
    location = next((b[len("location:"):].strip() for b in block if b.startswith("location:")), "")
    errors.append((Path(m.group("file")).name, int(m.group("line")), m.group("msg"), symbol, location))
    i += 1

by_file = {}
for e in errors:
    by_file.setdefault(e[0], []).append(e)

print(f"{len(errors)} errors in {len(by_file)} files\n")
for name in sorted(by_file, key=lambda n: (-len(by_file[n]), n)):
    print(f"== {name} ({len(by_file[name])})")
    for _f, line, msg, symbol, location in by_file[name]:
        extra = " | ".join(x for x in (symbol, location) if x)
        print(f"  {line:>5}  {msg}" + (f"  [{extra}]" if extra else ""))
