#!/usr/bin/env bash
# Build a real ARM64 Alpine Linux userspace inside an initramfs for QEMU.
# This is not Android 9. It replaces the Alpine "netboot" initramfs that
# stops with "Mounting boot media: failed" without a network/disk medium.
set -euo pipefail

OUTPUT="${1:?usage: build-alpine-initramfs.sh /path/to/output.cpio.gz}"
mkdir -p "$(dirname "$OUTPUT")"
OUTPUT="$(realpath -m "$OUTPUT")"
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

sudo mkdir -p "$TMP/rootfs"/{proc,sys,dev,run,tmp}
if [[ ! -e "$TMP/rootfs/dev/console" ]]; then
    sudo mknod -m 600 "$TMP/rootfs/dev/console" c 5 1
fi
if [[ ! -e "$TMP/rootfs/dev/null" ]]; then
    sudo mknod -m 666 "$TMP/rootfs/dev/null" c 1 3
fi
sudo chmod 1777 "$TMP/rootfs/tmp"

cat > "$TMP/init" <<'SCRIPT'
#!/bin/sh
# A tiny /init that demonstrates real userspace after the Linux kernel boots.
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
mount -t devtmpfs devtmpfs /dev 2>/dev/null || true
mount -t proc proc /proc 2>/dev/null || true
mount -t sysfs sysfs /sys 2>/dev/null || true
# This checks a *guest* DRM device. A framebuffer on the QEMU host alone is
# NOT proof that the Android guest has a working GPU driver.
if [ -e /sys/class/drm/card0 ]; then
  echo "VINEOS_QEMU_DRM_CARD_READY" >/dev/console
else
  echo "VINEOS_QEMU_DRM_CARD_MISSING" >/dev/console
fi
echo "VINEOS_ALPINE_USERLAND_READY arch=$(uname -m) release=$(cat /etc/alpine-release)" >/dev/console
echo "Alpine Linux is running inside rootless QEMU without a host ROOT request." >/dev/console
echo "This is a Linux test, NOT an Android 9 VM or functional guest Magisk." >/dev/console
# Keep this test init (PID 1) active even with no serial input.
exec /bin/sh -l </dev/console >/dev/console 2>&1
SCRIPT
sudo install -m 0755 "$TMP/init" "$TMP/rootfs/init"
sudo rm -f "$TMP/init"

# Preserve root ownership and character devices. Need sudo on the CI host to
# create a CPIO archive, but the generated QEMU guest runs without host ROOT.
(
    cd "$TMP/rootfs"
    sudo find . -print0 | sudo cpio --null -o -H newc --quiet | gzip -1 > "$OUTPUT"
)
test "$(stat -c %s "$OUTPUT")" -gt 1000000
echo "Built rootless ARM64 Alpine userspace initramfs: $(du -h "$OUTPUT" | cut -f1)"
sha256sum "$OUTPUT"
