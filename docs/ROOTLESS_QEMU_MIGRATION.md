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


## Android 9 ARM64 early-boot breakthrough (October 31, 2026)

The GitHub Actions run
[Android 9 QEMU early boot attempt](../../actions/runs/41239685278)
did the following **in a genuine rootless QEMU VM**:

1. Downloaded the real ARM64 Redroid 9.0.0 filesystem (~1.5 GiB
   uncompressed, with an ELF `/init` and API level 28).
2. Copied it into a virtio-attached ext4 disk.
3. Booted the Podroid ARM64 Linux 7.1.5 kernel in QEMU/TCG; the kernel
   successfully mounts BinderFS (binder-control works), but has no
   legacy `/dev/ashmem`.
4. Ran the `rootless-qemu/build-redroid9-stage0.sh` stage-0 loader,
   verified `/system/build.prop` was API28, and executed
   `switch_root /android /init`.
5. **Confirmed actual Android9 init** logged `init first stage started!`,
   `init second stage started!`, parsed `/init.rc`, then started services
   including `hwservicemanager`, `logd`, `gatekeeperd`, and vendor
   graphics service attempts.

**NOT a complete boot:** after 150 seconds several services were still
crashing and restarting. In the serial log, `logd` exits with status 255,
`vendor.gatekeeper-1-0` receives SIGABRT, and Android
`hwservicemanager` cannot find a graphics composer 2.1 interface. No
`sys.boot_completed=1`, Android home screen, Magisk/su privileges for
apps, touch interface, OpenGL or Standoff 2 was verified.

The complete serial boot log is stored as a GitHub Actions artifact:
[early boot log](../../actions/runs/41239685278).

A separate **Android 9 QEMU Early Boot APK** workflow builds an opt-in
*console-only* test that uses a user-selected `.vrom` archive from the
tablet's Downloads folder:
[APK workflow](../../actions/workflows/build-android9-qemu-apk.yml).
This tests whether the same real Android init process can run on the tablet,
not whether Android is usable yet. The user should not install Magisk or
other APKs into this early-boot guest.
