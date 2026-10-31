#!/usr/bin/env python3
"""Add an experimental Android 9 QEMU early boot screen to patched VineOS."""
import argparse
from pathlib import Path
import shutil

p=argparse.ArgumentParser()
p.add_argument("--project", type=Path, default=Path("upstream"))
root=p.parse_args().project
java=root/"app/src/main/java/com/hexadecinull/vineos"

for sub in ("domain/RootlessAndroid9QemuEngine.kt", "ui/screens/Android9QemuBootTestScreen.kt"):
    src=Path("overrides/app/src/main/java/com/hexadecinull/vineos") / sub
    dst=java / sub
    dst.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(src,dst)

def edit_once(path,old,new):
    content=path.read_text(encoding="utf-8")
    if content.count(old)!=1:
        raise RuntimeError(f"Expected exactly one anchor in {path}: {old[:90]}")
    path.write_text(content.replace(old,new,1),encoding="utf-8")

settings=java/"ui/screens/SettingsScreen.kt"
edit_once(settings,
    "    onQemuLinuxTest: () -> Unit,",
    "    onQemuLinuxTest: () -> Unit,\n    onAndroid9QemuTest: () -> Unit,")
edit_once(settings,
    """            SettingsSection(title = "About") {""",
    """            SettingsSection(title = "Android 9 QEMU experimental boot") {
                NavigateSettingsItem(
                    icon = Icons.Outlined.DeveloperBoard,
                    title = "Boot Android 9 .vrom (console only)",
                    subtitle = "Actual Android 9 init via QEMU, no launcher or root for apps",
                    onClick = onAndroid9QemuTest,
                )
            }

            SettingsSection(title = "About") {""")

main=java/"MainActivity.kt"
edit_once(main,
    "import com.hexadecinull.vineos.ui.screens.RootlessQemuDemoScreen",
    "import com.hexadecinull.vineos.ui.screens.RootlessQemuDemoScreen\n"+
    "import com.hexadecinull.vineos.ui.screens.Android9QemuBootTestScreen")
edit_once(main,
    '                    onQemuLinuxTest = { navController.navigate("rootless_qemu_linux_test") },',
    '                    onQemuLinuxTest = { navController.navigate("rootless_qemu_linux_test") },\n'+
    '                    onAndroid9QemuTest = { navController.navigate("qemu_android9_early_boot") },')
edit_once(main,
    '            composable(Screen.About.route) {',
    """            composable("qemu_android9_early_boot") {
                Android9QemuBootTestScreen(
                    onBack = { navController.popBackStack() },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            composable(Screen.About.route) {""")

gradle=root/"app/build.gradle.kts"
edit_once(gradle,
    'applicationIdSuffix = ".debug.rootlessqemu"',
    'applicationIdSuffix = ".debug.android9qemutest"')
strings=root/"app/src/main/res/values/strings.xml"
edit_once(strings,
    '<string name="app_name">VineOS QEMU Linux Test</string>',
    '<string name="app_name">VineOS Android9 QEMU Test</string>')
print("Android 9 QEMU test UI integrated without altering old VineOS installs")
