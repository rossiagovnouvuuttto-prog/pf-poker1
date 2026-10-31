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

# Official Android Emulator supports '-selinux permissive', which adds the
# required androidboot.selinux=permissive boot setting. The previous test
# unconditionally re-entered SELinux enforcing when the vendor policy loaded
# and failed to label /init, rebooting straight into bootloader. The upstream
# initwrap's early 'setenforce 0' runs before the SELinux policy is loaded, so
# it cannot prevent that first-stage transition. This is a test-only guest
# permission setting, never a host ROOT change or production default.
python3 - <<'PY_BOOT'
from pathlib import Path
p = Path('scripts/30-launch.sh')
src = p.read_text()
old = '-no-metrics -accel off -gpu swiftshader_indirect'
if src.count(old) != 1:
    raise SystemExit('Pinned emulator launch flags changed; refusing unsafe patch')
p.write_text(src.replace(old,
    '-no-metrics -selinux permissive -accel off -gpu swiftshader_indirect'))
print('Enabled diagnostic Android9 guest SELinux permissive boot')
PY_BOOT

# Start the real Google ARM64 Android9 emulator under software TCG.
CORES=1 MEM=4096 scripts/30-launch.sh
# Check early boot before spending 15+ minutes waiting for ADB that can never
# connect if Android init rebooted due to SELinux/restorecon.
sleep 15
if grep -Eq 'restorecon failed of /init|Reboot failed -- System halted' build/emu.log; then
    echo "EARLY_BOOT_FAILED_AND_HALTED" | tee build/gui-check/early-boot.txt
    tail -80 build/emu.log | tee -a build/gui-check/early-boot.txt
    exit 1
fi
echo "NO_EARLY_SELINUX_FATAL_DETECTED" > build/gui-check/early-boot.txt
sleep 8
log "Waiting for adb and systemReady (bounded to avoid an indefinitely hung test)"
set +e
timeout --signal=TERM --kill-after=5s 15m scripts/40-bringup.sh \
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
    'dumpsys activity activities' \
    'dumpsys window windows' \
    'wm size'; do
   echo ">>> $p" | tee -a build/gui-check/status.txt
   guest "$p" | tee -a build/gui-check/status.txt
done

log "Bring up the REAL Android 9 HOME activity, not just any nonblack frame"
# The upstream bring-up turns the screen off to preserve TCG throughput.
# Under QEMU software rendering this can leave a valid Android framework
# drawing nothing. Enable the display, explicitly request HOME, then give
# SurfaceFlinger/Launcher time to render.
guest "settings put global window_animation_scale 0"
guest "settings put global transition_animation_scale 0"
guest "settings put global animator_duration_scale 0"
guest "input keyevent 224"
guest "input keyevent 3"
guest "am start -a android.intent.action.MAIN -c android.intent.category.HOME"
log "Waiting for SurfaceFlinger and the home activity to draw"
sleep 60

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

# A nonblack capture might be only a boot animation. Require a running
# Android 9 framework, a real SurfaceFlinger service, and evidence that a
# launcher/home Activity is resumed before we call this a desktop candidate.
cat >build/gui-check/verify-home.py <<'PY_VERIFY'
from pathlib import Path
import re
status=Path("build/gui-check/status.txt").read_text(errors="replace")
frame=Path("build/gui-check/screenshot-result.txt").read_text(errors="replace")
def command_out(cmd):
    a=status.find(">>> "+cmd+"\n")
    if a<0: return ""
    s=status[a+len(">>> "+cmd)+1:]
    return s.split(">>> ",1)[0].strip()
sdk=command_out("getprop ro.build.version.sdk")
boot=command_out("getprop sys.boot_completed")
sf=command_out("service check SurfaceFlinger")
pkg=command_out("service check package")
activities=command_out("dumpsys activity activities")
home=bool(re.search(r"mResumedActivity.*(?:launcher|home)", activities, re.I))
framework=(sdk=="28" and boot=="1" and "found" in sf and "found" in pkg)
nonblack="NONBLACK_GRAPHICAL_FRAME_CAPTURED" in frame
ok=framework and nonblack and home
result=(
    "ANDROID9_HOME_SCREEN_VERIFIED" if ok else
    "ANDROID9_DESKTOP_NOT_VERIFIED"
)
detail=f"{result}\nandroid9_sdk={sdk!r} boot_completed={boot!r} SurfaceFlinger={sf!r} package={pkg!r} home_resumed={home} nonblack={nonblack}\n"
Path("build/gui-check/desktop-verification.txt").write_text(detail)
print(detail)
PY_VERIFY
python3 build/gui-check/verify-home.py
# A negative result is a useful diagnostic and should not discard the
# artifacts or be presented as a successful desktop build.
# Diagnostic workflow intentionally succeeds with a failed screenshot to
# retain evidence. The result file distinguishes pass from blocker.
