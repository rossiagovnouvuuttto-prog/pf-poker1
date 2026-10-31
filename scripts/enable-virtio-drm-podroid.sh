#!/usr/bin/env bash
# Modify a pinned *copy* of Podroid's ARM64 guest kernel config.
# This is required before QEMU's -device virtio-gpu-pci can be seen by Linux.
# Do not confuse a QEMU-provided framebuffer with a working Android GUI.
set -euo pipefail
PODROID="${1:?usage: enable-virtio-drm-podroid.sh /path/to/pinned/Podroid}"
CFG="$PODROID/podroid_kernel.config"
test -f "$CFG"
grep -q '^# CONFIG_DRM is not set$' "$CFG" || {
  echo "Expected upstream Podroid config changed: inspect DRM config" >&2; exit 1;
}
grep -q '^# CONFIG_FB is not set$' "$CFG" || {
  echo "Expected upstream Podroid config changed: inspect FB config" >&2; exit 1;
}
sed -i \
  -e 's/^# CONFIG_DRM is not set$/CONFIG_DRM=y/' \
  -e 's/^# CONFIG_FB is not set$/CONFIG_FB=y/' "$CFG"

cat >>"$CFG" <<'CONFIG'
# Required for a *real* GPU device on an ARM64 QEMU virt machine.
CONFIG_DRM_VIRTIO_GPU=y
CONFIG_DRM_FBDEV_EMULATION=y
CONFIG_DRM_FBDEV_OVERALLOC=100
CONFIG_DRM_VIRTIO_GPU_KMS=y
CONFIG_DRM_SHMEM_HELPER=y
CONFIG_DRM_KMS_HELPER=y
CONFIG_DMA_SHARED_BUFFER=y
CONFIG_VIRTIO_INPUT=y
CONFIG_INPUT_EVDEV=y
CONFIG_FRAMEBUFFER_CONSOLE=y
CONFIG_FB_VIRTUAL=y
CONFIG
echo "Applied guest DRM/KMS virtio GPU kernel options:"
grep -E '^CONFIG_(DRM|FB|DMA_SHARED_BUFFER|VIRTIO_INPUT)' "$CFG" | tail -25
