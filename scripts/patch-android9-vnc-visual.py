#!/usr/bin/env python3
"""Enable real (still experimental) QEMU VNC framebuffer output in Android9 APK.

Only apply for the VNC+DRM artifact variant. The stock Podroid QEMU release
contains --disable-vnc and MUST NOT be packaged with this patch.
"""
from pathlib import Path
import argparse
import shutil

p = argparse.ArgumentParser()
p.add_argument("--project", type=Path, default=Path("upstream"))
root = p.parse_args().project
java = root/"app/src/main/java/com/hexadecinull/vineos"
for sub in (
    "domain/QemuRfbFrameClient.kt",
    "ui/screens/QemuVncPreview.kt",
):
    src = Path("overrides/app/src/main/java/com/hexadecinull/vineos")/sub
    dst = java/sub
    dst.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, dst)

def edit(path, old, new):
    t = path.read_text(encoding="utf-8")
    if t.count(old) != 1:
        raise RuntimeError(f"Patch anchor mismatch {path}: {old[:90]!r}")
    path.write_text(t.replace(old, new, 1), encoding="utf-8")

engine = java/"domain/RootlessAndroid9QemuEngine.kt"
edit(engine,
    '                "-display", "none",',
    '''                // VNC on loopback only; requires a VNC-enabled QEMU binary
                // and a guest Linux kernel with actual DRM virtio-gpu.
                "-device", "virtio-gpu-pci",
                "-vnc", "127.0.0.1:2",''')
edit(engine,
    '                "-append", "console=ttyAMA0 rdinit=/init androidboot.hardware=redroid androidboot.selinux=permissive selinux=0",',
    '                "-append", "console=ttyAMA0 rdinit=/init androidboot.hardware=redroid androidboot.selinux=permissive selinux=0 androidboot.redroid_width=800 androidboot.redroid_height=600",')

ui = java/"ui/screens/Android9QemuBootTestScreen.kt"
edit(ui,
    '''        Text("Android 9 serial console", style = MaterialTheme.typography.titleMedium)''',
    '''        QemuVncPreview(enabled = launched && engine.running())
        HorizontalDivider()
        Text("Android 9 serial console", style = MaterialTheme.typography.titleMedium)''')
gradle=root/"app/build.gradle.kts"
edit(gradle,
    'applicationIdSuffix = ".debug.android9qemutest"',
    'applicationIdSuffix = ".debug.android9visualtest"')
strings=root/"app/src/main/res/values/strings.xml"
edit(strings,
    '<string name="app_name">VineOS Android9 QEMU Test</string>',
    '<string name="app_name">VineOS Android9 QEMU Video Test</string>')
print("Experimental Android9 RFB framebuffer and live screen preview wired in")
