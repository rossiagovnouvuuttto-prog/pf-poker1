#!/usr/bin/env python3
"""Package an EXISTING Android 9 Pie ext4 root filesystem for our VineOS fork.

Does not build Android, install Magisk, or guarantee guest boot. The input MUST
be an actual Android 9 rootfs with /init and /system/build.prop. Never create
fake Android files and label them a real ROM.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile

MAX_SIZE_BYTES = 16 * 1024**3
EXT4_MAGIC_OFFSET = 1024 + 56
EXT4_MAGIC = b"\x53\xef"

class InvalidRom(ValueError):
    pass

def run_debugfs(image: Path, command: str) -> str:
    if not shutil.which("debugfs"):
        raise InvalidRom("debugfs (e2fsprogs) is required to inspect the ROM")
    completed = subprocess.run(
        ["debugfs", "-R", command, str(image)],
        text=True,
        capture_output=True,
        check=False,
        timeout=60,
    )
    result = completed.stdout + "\n" + completed.stderr
    if completed.returncode != 0 or "File not found" in result or "not found by ext2_lookup" in result:
        raise InvalidRom(f"Missing or unreadable Android filesystem entry: {command}")
    return result

def ensure_entry(image: Path, path: str) -> None:
    result = run_debugfs(image, f"stat {path}")
    if "Inode:" not in result:
        raise InvalidRom(f"Required guest file missing: {path}")

def read_build_prop(image: Path) -> str:
    for candidate in ("/system/build.prop", "/build.prop"):
        try:
            value = run_debugfs(image, f"cat {candidate}")
            if "ro.build.version.sdk=" in value:
                return value
        except InvalidRom:
            continue
    raise InvalidRom("Could not locate Android build.prop within the root filesystem")

def validate_android9(image: Path, require_su: bool) -> dict:
    if not image.is_file() or image.stat().st_size < 1024 * 1024:
        raise InvalidRom("Expected an actual ext4 Android 9 rootfs image (at least 1 MiB)")
    if image.stat().st_size > MAX_SIZE_BYTES:
        raise InvalidRom("Image is larger than VineOS 16 GiB image limit")
    with image.open("rb") as f:
        f.seek(EXT4_MAGIC_OFFSET)
        if f.read(2) != EXT4_MAGIC:
            raise InvalidRom("Not an ext4 image; Android system.img alone may not be a rootfs")
    ensure_entry(image, "/init")
    prop_text = read_build_prop(image)
    version = re.search(r"(?m)^ro\.build\.version\.sdk=(\d+)\s*$", prop_text)
    if not version or int(version.group(1)) != 28:
        raise InvalidRom("Image does not identify as Android 9 / API 28")
    for prop in ("ro.build.version.release", "ro.product.cpu.abi"):
        if prop not in prop_text:
            print(f"Warning: {prop} not found in build.prop", file=sys.stderr)
    su_candidates = ("/system/xbin/su", "/system/bin/su", "/sbin/su")
    su_present = any(
        "Inode:" in run_debugfs(image, f"stat {path}")
        if _entry_exists(image, path) else False
        for path in su_candidates
    )
    if require_su and not su_present:
        raise InvalidRom("No su executable found; do not label this image as rooted")
    return {"apiLevel": 28, "suFilePresent": su_present}

def _entry_exists(image: Path, path: str) -> bool:
    try:
        ensure_entry(image, path)
        return True
    except InvalidRom:
        return False

def checksum(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()

def pack(image: Path, output: Path, require_su: bool) -> None:
    info = validate_android9(image, require_su)
    manifest = {
        "id": "vine-rom-9-experimental",
        "displayName": "Android 9 Pie (experimental image)",
        "androidVersion": "9.0",
        "apiLevel": 28,
        "supportedAbis": ["arm64-v8a"],
        "has32BitSupport": False,
        "releaseDate": datetime.now(timezone.utc).strftime("%Y-%m-%d"),
        "imageFile": "rootfs.img",
        "imageSha256": checksum(image),
        "suFilePresent": info["suFilePresent"],
        "rootFunctionalTested": False,
        "bootFunctionalTested": False,
        "format": "vineos-fork-rootfs-v1",
        "warning": "Packaging is NOT a boot test; host root and compatible HALs are still needed",
    }
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", allowZip64=True) as z:
        z.write(image, "rootfs.img", compress_type=zipfile.ZIP_STORED)
        z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False, indent=2))
    with zipfile.ZipFile(output, "r") as z:
        if z.testzip() is not None:
            output.unlink(missing_ok=True)
            raise InvalidRom("Archive CRC failed")
    print(f"Created: {output} ({output.stat().st_size} bytes)")
    print("CAUTION: Android 9 guest boot, ROOT and 3D GPU are NOT verified.")

def main() -> int:
    ap = argparse.ArgumentParser(description="Package an existing Android 9 ext4 guest rootfs into VineOS .vrom")
    ap.add_argument("--rootfs", required=True, type=Path, help="Bootable Android 9 ext4 root filesystem image")
    ap.add_argument("--output", required=True, type=Path, help="Target .vrom archive")
    ap.add_argument("--require-su", action="store_true", help="Reject images without a su file (presence != working root)")
    args = ap.parse_args()
    try:
        pack(args.rootfs, args.output, args.require_su)
    except (InvalidRom, OSError, subprocess.TimeoutExpired) as err:
        print(f"ERROR: {err}", file=sys.stderr)
        return 1
    return 0

if __name__ == "__main__":
    sys.exit(main())
