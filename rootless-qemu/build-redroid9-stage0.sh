#!/usr/bin/env bash
# Stage 0: mount Redroid Android 9 filesystem from a virtio disk,
# verify SDK 28, then try to switch PID 1 to the ACTUAL Android 9 /init.
#
# This does not claim that SurfaceFlinger, Binder devices, HALs, adb, or
# graphics will run. The serial log is the only authority.
set -euo pipefail
OUT="${1:?usage: build-redroid9-stage0.sh /absolute/path/to/initramfs}"
mkdir -p "$(dirname "$OUT")"
OUT="$(realpath -m "$OUT")"
TMP="$(mktemp -d)"
CID=""
cleanup() {
  if [[ -n "$CID" ]]; then docker rm -f "$CID" >/dev/null 2>&1 || true; fi
  sudo rm -rf "$TMP"
}
trap cleanup EXIT
docker pull --platform linux/arm64 alpine:3.22
CID="$(docker create --platform linux/arm64 alpine:3.22 /bin/true)"
mkdir -p "$TMP/rootfs"
docker export "$CID" | sudo tar --numeric-owner -xf - -C "$TMP/rootfs"
sudo mkdir -p "$TMP/rootfs"/{proc,sys,dev,run,tmp,android}
sudo mknod -m 600 "$TMP/rootfs/dev/console" c 5 1 2>/dev/null || true
sudo mknod -m 666 "$TMP/rootfs/dev/null" c 1 3 2>/dev/null || true
cat > "$TMP/init" <<'SCRIPT'
#!/bin/sh
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
log() { echo "$1" > /dev/console; }
mount -t devtmpfs devtmpfs /dev || log "ANDROID9_STAGE0_WARN devtmpfs not mounted"
mount -t proc proc /proc || log "ANDROID9_STAGE0_WARN proc not mounted"
mount -t sysfs sysfs /sys || log "ANDROID9_STAGE0_WARN sysfs not mounted"
log "ANDROID9_STAGE0_KERNEL_READY $(uname -r) $(uname -m)"
i=0
while [ ! -b /dev/vda ] && [ "$i" -lt 40 ]; do
  sleep 1
  i=$((i+1))
done
if [ ! -b /dev/vda ]; then
  log "ANDROID9_STAGE0_FAILED no virtio root disk /dev/vda"
  exec /bin/sh < /dev/console > /dev/console 2>&1
fi
log "ANDROID9_STAGE0_VIRTIO_DISK_FOUND"
if ! mount -t ext4 -o rw /dev/vda /android; then
  log "ANDROID9_STAGE0_FAILED cannot mount Android rootfs ext4"
  exec /bin/sh < /dev/console > /dev/console 2>&1
fi
log "ANDROID9_STAGE0_EXT4_MOUNTED"
if ! grep -q '^ro.build.version.sdk=28$' /android/system/build.prop; then
  log "ANDROID9_STAGE0_FAILED rootfs is not Android Pie (API28)"
  exec /bin/sh < /dev/console > /dev/console 2>&1
fi
log "ANDROID9_STAGE0_VERIFIED_ANDROID9_API28"
if [ ! -x /android/init ]; then
  log "ANDROID9_STAGE0_FAILED missing executable Android /init"
  exec /bin/sh < /dev/console > /dev/console 2>&1
fi
# The initramfs is already PID 1. /android is mounted ext4 from virtio.
# Move pseudo filesystems so Android init will retain them after switch_root.
mkdir -p /android/dev /android/proc /android/sys /android/run
mount --move /proc /android/proc || log "ANDROID9_STAGE0_WARN move proc"
mount --move /sys /android/sys || log "ANDROID9_STAGE0_WARN move sys"
mount --move /dev /android/dev || log "ANDROID9_STAGE0_WARN move dev"
echo "ANDROID9_STAGE0_HANDOFF_TO_ACTUAL_INIT" > /android/dev/console
# 'switch_root' changes the root mount and execs Android 9's native PID 1.
# It may fail if Android requires unavailable kernel drivers or vendor HALs.
exec /sbin/switch_root /android /init > /android/dev/console 2>&1
echo "ANDROID9_STAGE0_FAILED switch_root returned" > /android/dev/console
exec /bin/sh < /android/dev/console > /android/dev/console 2>&1
SCRIPT
sudo install -m 0755 "$TMP/init" "$TMP/rootfs/init"
(
 cd "$TMP/rootfs"
 sudo find . -print0 | sudo cpio --null -o -H newc --quiet | gzip -1 > "$OUT"
)
test "$(stat -c %s "$OUT")" -gt 1000000
echo "Prepared early Android9 rootfs loader: $OUT"
