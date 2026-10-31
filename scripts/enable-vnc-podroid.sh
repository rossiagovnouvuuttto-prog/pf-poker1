#!/usr/bin/env bash
# Patch the pinned Podroid QEMU build to add the VNC framebuffer transport.
# Podroid's release deliberately builds QEMU with --disable-vnc and cannot
# show any QEMU graphics through a remote frame client on Android.
set -euo pipefail
PODROID="${1:?usage: enable-vnc-podroid.sh /path/to/pinned/Podroid}"
FILE="$PODROID/Dockerfile"
test -f "$FILE"
if [ "$(grep -o -- '--disable-vnc' "$FILE" | wc -l)" -ne 1 ]; then
    echo "Expected exactly one disabled VNC flag in pinned Podroid Dockerfile" >&2
    exit 1
fi
# VNC is a network-based framebuffer. No GTK, SDL or platform display libraries
# are required, which is important for Android ARM64 / Bionic.
sed -i 's/--disable-vnc/--enable-vnc/' "$FILE"
grep -F -- '--enable-vnc' "$FILE"
echo "QEMU VNC framebuffer support enabled in pinned cross-build recipe"
