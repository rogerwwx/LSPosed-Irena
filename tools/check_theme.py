"""Run the manager's pure Java theme checks without an Android SDK (JDK 21+)."""
from pathlib import Path
import subprocess
import xml.etree.ElementTree as ET
import struct
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app/build/theme-check"
OUT.mkdir(parents=True, exist_ok=True)
sources = list((ROOT / "app/src/main/java/io/material/color/utilities").rglob("*.java"))
for annotation in ("NonNull", "Nullable"):
    stub = OUT / f"stubs/androidx/annotation/{annotation}.java"
    stub.parent.mkdir(parents=True, exist_ok=True)
    stub.write_text(f"package androidx.annotation; public @interface {annotation} {{}}", encoding="utf-8")
    sources.append(stub)
for name in ("ThemeConfig", "ResolvedPalette", "SeedSelection"):
    sources.append(ROOT / f"app/src/main/java/org/lsposed/manager/theme/{name}.java")
sources += list((ROOT / "app/src/testTheme/java").rglob("*.java"))
sources.append(ROOT / "app/src/main/java/org/lsposed/manager/util/monet/ColorResourcesTable.java")
args = OUT / "sources.txt"
args.write_text("\n".join(f'"{p.as_posix()}"' for p in sources), encoding="utf-8")
subprocess.run(["javac", "--release", "21", "-encoding", "UTF-8", "-d", str(OUT / "classes"), "@" + str(args)], check=True)
subprocess.run(["java", "-cp", str(OUT / "classes"), "org.lsposed.manager.theme.ThemeChecks", str(OUT)], check=True)
subprocess.run(["java", "-cp", str(OUT / "classes"), "org.lsposed.manager.util.monet.ResourceTableFixture", str(OUT)], check=True)

# Independently decode the emitted table: sparse indexes, day/night qualifiers,
# type IDs and ARGB values must survive renamed entry metadata.
data = (OUT / "palette.arsc").read_bytes()
u16 = lambda p: struct.unpack_from("<H", data, p)[0]
u32 = lambda p: struct.unpack_from("<I", data, p)[0]
expected = {int(i, 16): (int(light, 16), int(dark, 16)) for i, light, dark in
            (line.split() for line in (OUT / "palette-expected.txt").read_text().splitlines())}
assert u16(0) == 2 and u32(4) == len(data) and u32(8) == 1
package = u16(2) + u32(u16(2) + 4)
assert u16(package) == 0x200 and u32(package + 8) == 0x7f
position = package + u16(package + 2)
found = {False: {}, True: {}}
while position < len(data):
    chunk_type, header, size = u16(position), u16(position + 2), u32(position + 4)
    assert size >= header and position + size <= len(data)
    if chunk_type == 0x201:
        night = bool(data[position + 20 + 29] & 0x20)
        for index in range(u32(position + 12)):
            offset = u32(position + header + 4 * index)
            if offset == 0xffffffff:
                continue
            entry = position + u32(position + 16) + offset
            value = entry + u16(entry)
            assert data[value + 3] == 0x1c
            rid = 0x7f000000 | (data[position + 8] << 16) | index
            found[night][rid] = u32(value + 4)
    position += size
assert position == len(data)
for night in (False, True):
    assert found[night] == {rid: colors[int(night)] for rid, colors in expected.items()}
print(f"PASS: {len(expected)} sparse/renamed resource IDs in both day/night configurations")

for file in (ROOT / "app/src/main/res").rglob("*.xml"):
    ET.parse(file)
print("PASS: resource XML parses")

role_source = (ROOT / "app/src/main/java/org/lsposed/manager/theme/ResolvedPalette.java").read_text()
roles = set(re.findall(r'ROLES.put\("(\w+)"', role_source))
loader_source = (ROOT / "app/src/main/java/org/lsposed/manager/util/monet/MonetPalette.java").read_text()
bindings = dict(re.findall(r'ROLE_IDS.put\("(\w+)", R.color.(\w+)\)', loader_source))
assert roles == bindings.keys()
assert len(set(bindings.values())) == len(bindings), "Roles must have distinct runtime resource IDs"
baseline = {}
for line in (OUT / "baseline-colors.txt").read_text().splitlines():
    if line in ("LIGHT", "DARK"):
        mode = line
        baseline[mode] = {}
    else:
        role, value = line.split("=")
        baseline[mode][role] = int(value.removeprefix("#"), 16)
for mode in ("values", "values-night"):
    colors = ET.parse(ROOT / f"app/src/main/res/{mode}/colors_m3e_palette.xml").getroot()
    assert set(bindings.values()) == {c.attrib["name"] for c in colors}
    declared = {c.attrib["name"]: int(c.text.strip().removeprefix("#"), 16) for c in colors}
    expected_roles = baseline["DARK" if mode == "values-night" else "LIGHT"]
    assert declared == {bindings[role]: value for role, value in expected_roles.items()}
print("PASS: generated roles and resource targets match")
print("PASS: packaged default colors match the generated blue palette")
