#!/usr/bin/env bash
# A real kernel-level Binder probe for QEMU ARM64.
# Only prints ANDROID9_QEMU_BINDER_READY if binderfs mounts and provides
# binder-control. This is not proof that Android 9 services have booted.
set -euo pipefail
OUT="${1:?usage: build-android9-binder-probe.sh /absolute/path/to/initramfs.cpio.gz}"
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
sudo mkdir -p "$TMP/rootfs"/{proc,sys,dev,run,tmp}
sudo mkdir -p "$TMP/rootfs/dev/binderfs"
sudo mknod -m 600 "$TMP/rootfs/dev/console" c 5 1 || true
sudo mknod -m 666 "$TMP/rootfs/dev/null" c 1 3 || true
cat >"$TMP/init" <<'SCRIPT'
#!/bin/sh
export PATH=/bin:/sbin:/usr/bin:/usr/sbin
mount -t devtmpfs devtmpfs /dev 2>/dev/null || true
mount -t proc proc /proc 2>/dev/null || true
mount -t sysfs sysfs /sys 2>/dev/null || true
mkdir -p /dev/binderfs
echo "ANDROID9_QEMU_KERNEL_STARTED arch=$(uname -m) kernel=$(uname -r)" >/dev/console
if grep -qw binder /proc/filesystems && mount -t binder binder /dev/binderfs; then
    if [ -e /dev/binderfs/binder-control ]; then
        echo "ANDROID9_QEMU_BINDER_READY binderfs-control=present" >/dev/console
    else
        echo "ANDROID9_QEMU_BINDER_FAILED binder-control=missing" >/dev/console
    fi
elif grep -qw binderfs /proc/filesystems && mount -t binderfs binder /dev/binderfs; then
    if [ -e /dev/binderfs/binder-control ]; then
        echo "ANDROID9_QEMU_BINDER_READY binderfs-control=present" >/dev/console
    else
        echo "ANDROID9_QEMU_BINDER_FAILED binder-control=missing" >/dev/console
    fi
else
    echo "ANDROID9_QEMU_BINDER_FAILED kernel-binderfs=not-available" >/dev/console
fi
if [ -e /dev/ashmem ]; then
    echo "ANDROID9_QEMU_ASHMEM_READY" >/dev/console
else
    echo "ANDROID9_QEMU_ASHMEM_MISSING (Android Pie may need ashmem compatibility)" >/dev/console
fi
echo "ANDROID9_QEMU_KERNEL_PROBE_DONE" >/dev/console
exec /bin/sh -l </dev/console >/dev/console 2>&1
SCRIPT
sudo install -m 0755 "$TMP/init" "$TMP/rootfs/init"
(
  cd "$TMP/rootfs"
  sudo find . -print0 | sudo cpio --null -o -H newc --quiet | gzip -1 >"$OUT"
)
test "$(stat -c %s "$OUT")" -gt 1000000
echo "Built Alpine ARM64 Binder diagnostic initramfs $OUT"
