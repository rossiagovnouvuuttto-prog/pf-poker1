# QEMU binary provenance — experimental VineOS Linux test

The dedicated GitHub Actions workflow
`.github/workflows/build-rootless-linux-test-apk.yml` fetches **Podroid v1.2.9**
(publicly released 2026-09-21, GPL-2.0-or-later source headers) and extracts
its **Android ARM64 QEMU executable** (`libqemu-system-aarch64.so`) plus
the corresponding `libslirp.so`. Those are used as *separate executables*,
not linked into VineOS's own C++ JNI runtime.

## Upstream source and build recipe

- Podroid source tree **exact commit**:
  https://github.com/ExTV/Podroid/tree/ce0121896b2895637ab1f656cbb0d75fa2874489
- QEMU source version in this upstream build: **11.0.4**.
- Podroid build recipe:
  https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/build-all.sh
- Podroid license:
  https://github.com/ExTV/Podroid/blob/ce0121896b2895637ab1f656cbb0d75fa2874489/LICENSE
- QEMU upstream source and license:
  https://gitlab.com/qemu-project/qemu/-/tags/v11.0.4
- Android ARM64 binary release:
  https://github.com/ExTV/Podroid/releases/download/v1.2.9/Podroid-v1.2.9-release.apk
- Verified APK SHA-256:
  `3945c8c887f1df239501de6320fe5c8315bf9805029f530373d3244fee0d2a5f`.

When distributing the derived VineOS debug APK, include upstream license
notices and provide access to the exact Corresponding Source, toolchain
patches, build scripts, and dependencies. The respective authors retain
their copyright. Use this experimental artifact for testing and
source-available development; do not redistribute stripped of these notices.

## Guest test images

The test VM downloads Alpine Linux v3.22 ARM64 `vmlinuz-virt` and
`initramfs-virt` from
https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/netboot/.
This boots Linux in QEMU/TCG, **not Android 9 Pie**. This is an incremental
test of rootless execution on Android; it does not claim guest graphics,
Magisk, Android userspace, or gaming compatibility.
