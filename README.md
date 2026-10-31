# VineOS APK build (experimental)

GitHub Actions build harness for the **public** VineOS `v0.1.0-alpha` source:
https://github.com/Hexadecinull/VineOS

The source is pinned to upstream commit
`e2b1f47dea86969579b6469f74fad2d0b36edad2` to avoid silently changing builds.

## Download APK
Open **Actions → Build VineOS APK → latest successful run → Artifacts → VineOS-debug-APK**.

This workflow produces an installable **debug APK** for testing. It does **not**
implement a bootable Android 9/11/12 ROM, guest ROOT, GPU acceleration, or
Standoff 2 compatibility. These features require significant changes in the
upstream virtualization engine.

Original project: © its contributors, GPL-3.0.
