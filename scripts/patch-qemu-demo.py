#!/usr/bin/env python3
"""Wire an opt-in rootless QEMU Linux-only boot test into VineOS Settings."""
import argparse
from pathlib import Path
import shutil

p = argparse.ArgumentParser()
p.add_argument("--project", type=Path, default=Path("upstream"))
root = p.parse_args().project
java = root / "app/src/main/java/com/hexadecinull/vineos"

src = Path("overrides/app/src/main/java/com/hexadecinull/vineos/ui/screens/RootlessQemuDemoScreen.kt")
dst = java / "ui/screens/RootlessQemuDemoScreen.kt"
dst.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(src, dst)

def edit_once(path, before, after):
    text = path.read_text(encoding="utf-8")
    if text.count(before) != 1:
        raise RuntimeError(f"Could not find exact patch target in {path.name}: {before!r}")
    path.write_text(text.replace(before, after, 1), encoding="utf-8")

settings = java / "ui/screens/SettingsScreen.kt"
edit_once(settings,
    "    onAboutClick: () -> Unit,",
    "    onAboutClick: () -> Unit,\n    onQemuLinuxTest: () -> Unit,")
edit_once(settings,
    """            SettingsSection(title = "About") {""",
    """            SettingsSection(title = "Experimental rootless QEMU") {
                NavigateSettingsItem(
                    icon = Icons.Outlined.DeveloperBoard,
                    title = "Boot ARM64 Linux test VM",
                    subtitle = "QEMU proof of concept, no tablet ROOT. Not Android 9 yet.",
                    onClick = onQemuLinuxTest,
                )
            }

            SettingsSection(title = "About") {""")
main = java / "MainActivity.kt"
edit_once(main,
    "import com.hexadecinull.vineos.ui.screens.ROMsScreen",
    "import com.hexadecinull.vineos.ui.screens.ROMsScreen\n"
    "import com.hexadecinull.vineos.ui.screens.RootlessQemuDemoScreen")
edit_once(main,
    "                    onAboutClick = { navController.navigate(Screen.About.route) },",
    "                    onAboutClick = { navController.navigate(Screen.About.route) },\n"
    "                    onQemuLinuxTest = { navController.navigate(\"rootless_qemu_linux_test\") },")
edit_once(main,
    """            composable(Screen.About.route) {""",
    """            composable("rootless_qemu_linux_test") {
                RootlessQemuDemoScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable(Screen.About.route) {""")
gradle = root / "app/build.gradle.kts"
edit_once(gradle,
    """        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }""",
    """        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // The Android ARM64 QEMU executable is an ELF file packaged as
        // libqemu-system-aarch64.so. ProcessBuilder needs the real path.
        jniLibs {
            useLegacyPackaging = true
        }""")
# This demo is its own installable package. Other VineOS versions and
# imported ROMs remain untouched. GitHub debug keys are ephemeral.
edit_once(gradle,
    'applicationIdSuffix = ".debug.fgsfix.diag2"',
    'applicationIdSuffix = ".debug.rootlessqemu"')
strings = root / "app/src/main/res/values/strings.xml"
edit_once(strings,
    '<string name="app_name">VineOS Diagnostics 2</string>',
    '<string name="app_name">VineOS QEMU Linux Test</string>')

print("Rootless QEMU Linux test added as a separate APK under Settings")
