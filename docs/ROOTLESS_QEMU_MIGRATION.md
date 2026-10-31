# Rootless ARM64 VM track — real milestones, not fake "Android 9 ready"

**Target:** Redmi Pad 2 Pro, Android 16, Xiaomi HyperOS 3, no host root;
Android 9 guest with app-level root, and ultimately testing Standoff 2.

## Why VineOS alpha displays black

The current native code `app/src/main/cpp/container/namespace_manager.cpp`
opens `/dev/loop-control`, attaches loop devices with `LOOP_SET_FD`,
calls `mount(2)`, `unshare(CLONE_NEWPID | CLONE_NEWNS | ...)`, and
`pivot_root(2)`. These require Linux privileges which a stock third-party
Android app does not have. The pre-exported Redroid 9 `.vrom` contains a
filesystem; it cannot grant the host's CAP_SYS_ADMIN.

The display code expects `/dev/graphics/fb0` in the guest, whereas Redroid
uses an Android container compositor, not a Linux framebuffer suitable for
VineOS's existing bridge. Even with host root, a black screen is possible.

## Work delivered in this repository

- [x] Document and fix earlier bogus boot-status diagnostic.
- [x] `RootlessQemuEngine.kt`: new software emulation process backend that
  never needs host root, loop devices or Linux namespaces.
- [x] Rootless ARM64/Linux **kernel boot smoke-test** workflow on a GitHub
  runner, using QEMU `virt` with TCG and Alpine netboot files.
- [ ] Package Android-built ARM64 `qemu-system-aarch64` executable
  (and runtime dependencies), with complete source/build and licenses.
- [ ] Build a compatible bootable **Android 9** kernel/initramfs/system/vendor
  bundle for QEMU `virt`, using Android-specific Binder support.
- [ ] Boot Android 9 on a **real Android 16 device**, verify `sys.boot_completed`.
- [ ] Implement touch display, Wayland/SurfaceFlinger/GPU or VNC path.
- [ ] Install and verify guest ROOT (e.g., `su` permissions and manager).
- [ ] Run app installation tests (Aurora Store, Magisk manager, Shizuku).
- [ ] Benchmark Standoff 2, which may not function or achieve usable FPS.

## The ROM formats are not interchangeable

- `vine-rom-9-redroid-unverified.vrom`: ext4 filesystem for the old
  privileged VineOS container engine; does not include a bootable QEMU
  kernel / ramdisk / platform HALs. **Do not feed it to QEMU.**
- `qemu-guest/kernel` and `qemu-guest/initramfs`: first inputs to the
  new full-system emulator. This boots *a Linux kernel*, not necessarily
  Android. The initial CI uses **Alpine Linux, not Android 9**.

## Performance expectation

QEMU TCG emulates CPU instructions in software and will be significantly
slower than a GPU-accelerated, hardware-virtualized Android VM.
Running Linux rootless is a meaningful milestone. Achieving high-FPS
3D games with no KVM, no virgl, and no GPU passthrough is a separate,
much harder problem. We must never label QEMU kernel boot as
"Android 9 with ROOT and Standoff 2 ready".

Android Virtualization Framework is a poor general fallback here: its
Java APIs require `MANAGE_VIRTUAL_MACHINE` and support Microdroid VMs,
not an arbitrary Android 9 graphics guest available to a normal APK.
