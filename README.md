# OverlayMod

Standalone ARM64 Android APK with a root app_process helper, a transparent
SurfaceControl layer, Dear ImGui/OpenGL ES rendering and process_vm_readv diagnostics.

## Build

GitHub Actions: Build APK runs on pushes to main/master and workflow_dispatch.
Download OverlayMod-debug from the successful run's Artifacts section.
The ZIP contains the debug-signed APK and its SHA-256 checksum.

Local/CI command: ./gradlew --no-daemon assembleDebug
Requires JDK 17, Python 3, curl and an Android SDK. CI installs the pinned NDK/CMake.
Gradle 8.9; AGP 8.7.3; NDK 27.2.12479018; CMake 3.22.1; ImGui v1.91.8.

## Run

Install the APK on an ARM64 rooted Android 10+ device.
Optionally enter a target PID; an empty PID uses the root helper itself.
Press Start root overlay and approve su. Stop using the Stop overlay button.
The display-only layer does not capture touches or hide from screenshots.

## Configuration and limits

All target-specific offsets in main.cpp are 0x0. Zero addresses are never read.
The overlay performs a process_vm_readv self-test. A target diagnostic read
is enabled only by setting READ_TEST_ADDRESS to a validated absolute address.
Player enumeration, ESP, health/team and aiming are not implemented because
target layouts and pointer chains have not been established.

Root is not an Android manifest permission. Android hidden SurfaceControl APIs,
SELinux policy and OEM changes can prevent rendering or cross-process reading.
Display dimensions are captured at startup; stop/restart after rotating.
Build verification does not establish target-game runtime compatibility.
