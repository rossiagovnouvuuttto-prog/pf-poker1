# Android 9 graphical desktop bring-up — verified state (2026-11-01)

## Confirmed on Redmi Pad 2 Pro (unrooted HyperOS 3)

- VineOS's old container runtime does not have CAP_SYS_ADMIN and cannot
  launch its containerized ROM. This is a host permission limit.
- A distinct rootless ARM64 QEMU/TCG backend successfully boots Linux
  kernel + Alpine ARM64 userspace on the tablet.
- The `VineOS Android9 QEMU EarlyBoot` app is a **serial-console boot
  tester** that imports a user-selected Redroid 9 `.vrom`. It is **not**
  a usable desktop guest with installed apps.

## Confirmed on GitHub CI — actual Android 9 (API 28)

The validated Redroid 9 container filesystem was connected to a virtio
ext4 disk in QEMU, and the guest reached Android's first- and second-stage
`init`. That is real Android userspace, not a simulated image.

The full serial log was re-read in:
[Redroid graphics blocker analysis](../../actions/runs/41244526918).

Verified barriers in the log (not inferred):
- `vendor.hwcomposer-2-1` crashes/restarts repeatedly with exit status 1
  starting at ~68 seconds. This vendor hardware compositor was written
  for the Redroid container graphics topology, **not** generic QEMU `virt`.
- `logd` exits with status 255 repeatedly.
- `vendor.gatekeeper-1-0` receives SIGABRT repeatedly.
- The guest never reports a verified `sys.boot_completed=1` nor
  presents a tested home-screen frame.

**A QEMU native process starting, Android init running, or a static black
SurfaceView do not prove the Android desktop is working.** Do not ship an
APK claiming a graphical Android 9 until a genuine guest screenshot or live
SurfaceFlinger frame is verified.

## New graphical boot branch

The Redroid image is an Android-container filesystem, not a complete
virtual-device image. Instead of attempting to force its vendor graphics HAL
to speak QEMU `virt`, use the official Google API 28 `google_apis/arm64-v8a`
Android emulator image, with matching `kernel-ranchu`, `vendor.img`
and its bundled software-rendering stack.

New reproducible test:
[Android 9 official emulator GUI proof](../../actions/workflows/android9-official-gui-probe.yml)

- Exact upstream MIT-licensed source reference:
  `lhns/android-arm-tcg@f9eecf6c0d2fcc1e255b5a6d7322b07cbe017ba9`
  (documented Android API 28 ARM64 software-TCG bring-up).
- Downloads an official Android 9 ARM64 emulator image using sdkmanager.
- Uses pinned emulator build, init-wrapper and documented byte-patches to
  try to bring up the guest's Android framework, Binder and vendor stack.
- Captures actual `adb exec-out screencap -p`, SurfaceFlinger status,
  boot property checks and logs as a CI artifact, if possible.
- This test runs on a GitHub runner, **not yet on the Redmi tablet**.
- A successful `systemReady` alone does **not** mean a graphical desktop;
  the probe records screenshot dimensions, image variance and boot services.

## Remaining milestones

1. Obtain and verify a real graphical frame from the Android 9 guest.
2. Implement an emulator-compatible display and touch transport inside the
   Android APK rather than the old black VineOS container screen.
3. Verify a real Android 9 home screen **on Redmi Pad 2 Pro**.
4. Add and test guest-only ROOT after desktop and system services are stable.
5. Investigate GPU acceleration and game compatibility. QEMU TCG software
   emulation alone is unlikely to deliver playable Standoff 2 framerates.

Do not suggest downloading another "fixed" APK without verified functionality.
