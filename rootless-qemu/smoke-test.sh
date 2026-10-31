#!/usr/bin/env bash
# Rootless ARM64 machine smoke test on an ordinary unprivileged Linux process.
# This proves the QEMU backend design can boot a kernel without mount(),
# unshare(), /dev/loop-control, KVM, or CAP_SYS_ADMIN.
#
# It deliberately boots Alpine Linux (NOT Android 9). A Redroid .vrom
# exported from a container is not a QEMU boot image.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
cd out
BASE="https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/netboot"
curl --fail --location --retry 3 --silent --show-error "${BASE}/vmlinuz-virt" -o kernel
curl --fail --location --retry 3 --silent --show-error "${BASE}/initramfs-virt" -o initramfs

test "$(stat -c '%s' kernel)" -gt 1000000
test "$(stat -c '%s' initramfs)" -gt 1000000
command -v qemu-system-aarch64

# No sudo, no host root. Timeout means the guest continued running and is
# normal for a VM waiting at a login/shell prompt. Capture the serial trace.
set +e
timeout 130s qemu-system-aarch64 \
    -machine virt \
    -accel tcg,thread=multi \
    -cpu cortex-a57 \
    -smp 2 \
    -m 1024 \
    -kernel kernel \
    -initrd initramfs \
    -append "console=ttyAMA0" \
    -display none \
    -serial stdio \
    -monitor none \
    -nic user,model=virtio-net-pci \
    -no-reboot < /dev/null > serial.log 2>&1
exitcode=$?
set -e

if ! grep -Eq 'Linux version|Booting Linux|Welcome to Alpine' serial.log; then
    echo "QEMU failed to reach Linux kernel, process exit code: $exitcode" >&2
    tail -100 serial.log >&2
    exit 1
fi
echo "PASS: real ARM64 Linux kernel booted under rootless QEMU/TCG"
grep -E 'Linux version|Booting Linux|Welcome to Alpine' serial.log | head -5
echo "NOTE: this does not demonstrate Android 9 boot or GPU acceleration"
