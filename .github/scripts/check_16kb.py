"""Report native libraries in an APK that aren't ready for 16 KB memory pages.

Google Play requires apps targeting Android 15+ to support 16 KB page sizes.
For each .so in the APK this checks every PT_LOAD segment's alignment
(must be >= 16384). It prints GitHub warning annotations and exits 0, so a
problem is visible on the run without blocking the build.

Usage: python3 check_16kb.py <apk> [<apk> ...]
"""
import struct
import sys
import zipfile

PT_LOAD = 1
NEEDED = 16 * 1024


def load_alignments(data: bytes):
    if data[:4] != b"\x7fELF":
        return []
    is64 = data[4] == 2
    little = data[5] == 1
    e = "<" if little else ">"
    if is64:
        phoff = struct.unpack_from(e + "Q", data, 0x20)[0]
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x36)
    else:
        phoff = struct.unpack_from(e + "I", data, 0x1C)[0]
        phentsize, phnum = struct.unpack_from(e + "HH", data, 0x2A)
    out = []
    for i in range(phnum):
        off = phoff + i * phentsize
        p_type = struct.unpack_from(e + "I", data, off)[0]
        if p_type != PT_LOAD:
            continue
        align = struct.unpack_from(e + ("Q" if is64 else "I"), data, off + (0x30 if is64 else 0x1C))[0]
        out.append(align)
    return out


def main(paths):
    for apk in paths:
        bad = []
        count = 0
        with zipfile.ZipFile(apk) as z:
            for name in z.namelist():
                if not (name.startswith("lib/") and name.endswith(".so")):
                    continue
                # 32-bit libraries don't need 16 KB alignment.
                if not (name.startswith("lib/arm64-v8a/") or name.startswith("lib/x86_64/")):
                    continue
                count += 1
                aligns = load_alignments(z.read(name))
                if aligns and min(aligns) < NEEDED:
                    bad.append(f"{name} (LOAD align {min(aligns)})")
        label = apk.rsplit("/", 1)[-1]
        if bad:
            for b in bad:
                print(f"::warning title=16 KB page size::{label}: {b} is not 16 KB aligned; Play will reject it for Android 15+ targets")
        else:
            print(f"{label}: {count} 64-bit native libraries checked, all 16 KB aligned")


if __name__ == "__main__":
    main(sys.argv[1:])
