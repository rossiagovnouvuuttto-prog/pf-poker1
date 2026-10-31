#!/usr/bin/env python3
"""Apply transparent, reproducible VineOS alpha patches during GitHub Actions.

This is a small fork/overlay. We do not pretend that a rooted Android guest exists.
"""
from pathlib import Path
import argparse
import shutil

parser = argparse.ArgumentParser()
parser.add_argument("--project", type=Path, default=Path("upstream"))
args = parser.parse_args()
project = args.project

source = Path("overrides/app/src/main/java/com/hexadecinull/vineos/domain/GuestImageProvisioner.kt")
target = project / "app/src/main/java/com/hexadecinull/vineos/domain/GuestImageProvisioner.kt"
target.parent.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source, target)

vm = project / "app/src/main/java/com/hexadecinull/vineos/ui/viewmodel/CreateInstanceViewModel.kt"
text = vm.read_text(encoding="utf-8")

def replace_exact(before: str, after: str) -> None:
    global text
    if text.count(before) != 1:
        raise RuntimeError(f"Expected exactly one upstream match: {before[:100]!r}")
    text = text.replace(before, after, 1)

replace_exact(
    "import com.hexadecinull.vineos.native.VineRuntime",
    "import com.hexadecinull.vineos.domain.GuestImageProvisioner"
)
replace_exact("import java.io.File", "import java.util.UUID")
replace_exact(
    "    private val romRepo: ROMRepository,\n    private val prefs: AppPreferences,",
    "    private val romRepo: ROMRepository,\n    private val prefs: AppPreferences,\n    private val imageProvisioner: GuestImageProvisioner,"
)
start = text.index("            val isRooted = allowRootInstances.value && selectedRoot.value")
end = text.index("            val instance = VMInstance(", start)
text = (
    text[:start]
    + '''            // Never claim to have Magisk/root without installing a rooted guest.
            if (selectedRoot.value) {
                stateFlow.value = CreateInstanceState.Error(
                    "Guest ROOT is not implemented yet. Use an Android 9 rootfs when supported.",
                )
                return@launch
            }

            val instanceId = UUID.randomUUID().toString()
            val instancePath = try {
                imageProvisioner.provision(selectedRom, instanceId, storage)
            } catch (e: Exception) {
                stateFlow.value = CreateInstanceState.Error(
                    e.message ?: "Could not unpack guest rootfs.img.",
                )
                return@launch
            }

'''
    + text[end:]
)
replace_exact("                isRooted = isRooted,", "                isRooted = false,")
vm.write_text(text, encoding="utf-8")

ui = project / "app/src/main/java/com/hexadecinull/vineos/ui/screens/CreateInstanceScreen.kt"
txt = ui.read_text(encoding="utf-8")
before = '"Grants Magisk/root inside this instance"'
after = '"Not available yet: needs a rooted guest image and privileged host runtime"'
if txt.count(before) != 1:
    raise RuntimeError("Root toggle text changed upstream: need manual review")
txt = txt.replace(before, after)
before = "Switch(checked = root, onCheckedChange = { vm.selectedRoot.value = it })"
after = "Switch(checked = false, onCheckedChange = {}, enabled = false)"
if txt.count(before) != 1:
    raise RuntimeError("Root toggle changed upstream: need manual review")
txt = txt.replace(before, after)
ui.write_text(txt, encoding="utf-8")

print("VineOS alpha patched: guest ROM provisioning + disabled misleading root toggle")
