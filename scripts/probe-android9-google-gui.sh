#!/usr/bin/env bash
# Try the official Android 9 (API 28) ARM64 emulator image with its own
# ranchu kernel + vendor graphics stack, instead of container-only Redroid.
#
# Rootless/TCG test. A real Android 9 screenshot, not an illustration,
# is the success condition. Does NOT install root, Magisk, or Standoff 2.
set -euo pipefail
cd "${GOOGLE_EMU_PROJECT:?Set GOOGLE_EMU_PROJECT to pinned android-arm-tcg checkout}"
export SDK_ROOT="$PWD/sdk"
export ANDROID_I_WANT_MY_TCG=yes
export LIBGL_ALWAYS_SOFTWARE=1
mkdir -p "$PWD/build/gui-check"

log() { echo "[GUI TEST] $*"; }
log "Official Google API28 arm64 image + ranchu graphics probe"
log "Disk free space:"
df -h .

# Exact download, binary byte-patches, and initwrap were documented and tested
# upstream. Do not re-implement binary patch offsets or invent launch flags.
scripts/00-setup.sh
scripts/10-patch-image.sh
scripts/20-build-ramdisk.sh

# Start the real Google ARM64 Android9 emulator under software TCG.
CORES=1 MEM=4096 scripts/30-launch.sh
sleep 8
log "Waiting for adb, systemReady, and (ideally) graphical services"
set +e
timeout --signal=TERM --kill-after=5s 35m scripts/40-bringup.sh \
    >build/gui-check/bringup-host.log 2>&1
bringup_rc=$?
set -e
echo "BRINGUP_EXIT_CODE=$bringup_rc" | tee build/gui-check/status.txt

ADB="$SDK_ROOT/platform-tools/adb"
SERIAL="emulator-5554"
guest() { timeout 90 "$ADB" -s "$SERIAL" shell "$@" 2>&1 || true; }

# Always collect service/status facts, even if full systemReady failed.
for p in \
    'getprop ro.build.version.sdk' \
    'getprop sys.boot_completed' \
    'getprop init.svc.surfaceflinger' \
    'getprop init.svc.zygote' \
    'getprop init.svc.bootanim' \
    'getprop init.svc.hwservicemanager' \
    'service check SurfaceFlinger' \
    'service check display' \
    'service check package' \
    'wm size'; do
   echo ">>> $p" | tee -a build/gui-check/status.txt
   guest "$p" | tee -a build/gui-check/status.txt
done

log "Attempt to wake the guest display and capture an actual Android9 frame"
guest "input keyevent 224"
sleep 10
# Pipe binary bytes through adb exec-out, not shell CRLF-transformed output.
set +e
timeout 90 "$ADB" -s "$SERIAL" exec-out screencap -p \
   > build/gui-check/android9-screenshot.png 2> build/gui-check/screencap-error.log
screenshot_rc=$?
set -e
echo "SCREENSHOT_EXIT_CODE=$screenshot_rc" | tee -a build/gui-check/status.txt
file build/gui-check/android9-screenshot.png || true

# Capture Android logs from inside, not just QEMU kernel messages.
timeout 100 "$ADB" -s "$SERIAL" logcat -d -b all -t 3000 \
   > build/gui-check/logcat-tail.txt 2>&1 || true
tail -200 build/emu.log > build/gui-check/emulator-last200.txt || true

# Pillow is installed by the invoking workflow. Nonuniform pixels indicate
# a genuine drawn Android frame, not a manually generated wallpaper image.
python3 - <<'PY'
from pathlib import Path
p=Path("build/gui-check/android9-screenshot.png")
valid=False
if p.exists() and p.stat().st_size > 1000:
    try:
        from PIL import Image, ImageStat
        im=Image.open(p).convert("RGB")
        stat=ImageStat.Stat(im.resize((256,256)))
        spread=sum(stat.stddev)
        print(f"Screenshot dimensions={im.size}, channel stddev={stat.stddev}, spread={spread:.1f}")
        valid=(im.width>=320 and im.height>=320 and spread>6)
    except Exception as e: print(f"Screenshot decode error: {e}")
Path("build/gui-check/screenshot-result.txt").write_text(
   ("NONBLACK_GRAPHICAL_FRAME_CAPTURED" if valid else "NO_GRAPHICAL_FRAME_VERIFIED")+"\n"
)
print(Path("build/gui-check/screenshot-result.txt").read_text())
PY

# Do not claim graphical success from QEMU process start or systemReady alone.
if grep -q "NONBLACK_GRAPHICAL_FRAME_CAPTURED" build/gui-check/screenshot-result.txt &&
   grep -q "found" build/gui-check/status.txt; then
    echo "TRUE_ANDROID9_GUI_CANDIDATE: screenshot + services recorded"
else
    echo "GRAPHICAL_ANDROID9_NOT_YET_VERIFIED: logs and screenshot archived"
fi
# Diagnostic workflow intentionally succeeds with a failed screenshot to
# retain evidence. The result file distinguishes pass from blocker.
