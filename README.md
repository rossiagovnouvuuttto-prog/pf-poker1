# VineOS Android 9+ experiment

This repository contains a **reproducible patched APK build** of the public
[VineOS v0.1.0-alpha](https://github.com/Hexadecinull/VineOS) source,
pinned at commit `e2b1f47dea86969579b6469f74fad2d0b36edad2`.

## What's implemented

- GitHub Actions compiles a **debug APK** without paid signing services.
- `GuestImageProvisioner.kt` installs a selected `.vrom` file's actual
  `rootfs.img` into each newly created VM instance, checking archive
  integrity and ext4/squashfs filesystem magic.
- `scripts/patch-upstream.py` patches upstream's instance creation to
  use a non-empty UUID and actually provision that guest image.
- It **rejects requests to enable guest root** instead of falsely marking
  a VM rooted when Magisk was never installed.

## Download the APK

[GitHub Actions](../../actions/workflows/build-vineos.yml)
→ latest successful **Build VineOS APK** → **Artifacts** → `VineOS-debug-APK`.

You will receive a ZIP containing `app-debug.apk`, **not an Android 9 ROM**.

## Current blockers — this is NOT yet a working rooted Android 9 VM

1. A bootable, Android 9 (API 28) **container-compatible** ROM image
   containing the complete `/init` and `/system` tree does not exist in
   this repository. A standalone Android `system.img` or emulator
   `system.img` is not enough.
2. Upstream's Linux namespace engine needs `CAP_SYS_ADMIN` on the **host**
   Android OS. The code does not use Magisk to elevate the process, and on
   an unrooted Redmi Pad 2 Pro with Android 16 this privilege is normally
   unavailable. ROOT inside a guest does not solve host privileges.
3. Root **inside** Android 9 (Magisk, `su`, startup services, policies)
   still needs to be implemented and tested with a compatible guest image.
4. Android Binder, input, framebuffer and GPU acceleration of the
   container require further work; Standoff 2 compatibility is untested.

**Do not assume a successful APK build means the VM can boot Android 9.**
No fake Android 9 screens, fake root privileges or unverified ROM downloads
are included.

## Source layout

- `.github/workflows/build-vineos.yml`: builds on GitHub Actions
- `overrides/app/src/main/java/.../GuestImageProvisioner.kt`: ROM extraction
- `scripts/patch-upstream.py`: transparently patch selected upstream files

This fork is for development; original project is GPL-3.0.
