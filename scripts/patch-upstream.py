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

# Foreground-service fix for Android 16 / HyperOS 3.
service_source = Path(
    "overrides/app/src/main/java/com/hexadecinull/vineos/service/VineService.kt"
)
service_target = project / "app/src/main/java/com/hexadecinull/vineos/service/VineService.kt"
assert service_target.is_file(), "Upstream VineService missing"
shutil.copyfile(service_source, service_target)

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

# Wait for Android to promote the service before starting native code.
# The service starts on Android's main thread and reports readiness in onCreate.
manager = project / "app/src/main/java/com/hexadecinull/vineos/domain/VineVMManager.kt"
manager_text = manager.read_text(encoding="utf-8")
before_import = "import kotlinx.coroutines.delay"
after_import = """import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first"""
if manager_text.count(before_import) != 1:
    raise RuntimeError("Unexpected upstream coroutine imports")
manager_text = manager_text.replace(before_import, after_import, 1)
before_start = """            ContextCompat.startForegroundService(
                context,
                Intent(context, VineService::class.java).apply { action = VineService.ACTION_START },
            )

            val handle = VineRuntime.startInstance("""
after_start = """            // Avoid ForegroundServiceDidNotStartInTimeException on HyperOS 3.
            // Do not start heavy native VM code until Android acknowledges FGS.
            val foregroundStarted = try {
                withContext(Dispatchers.Main.immediate) {
                    ContextCompat.startForegroundService(
                        context,
                        Intent(context, VineService::class.java).apply {
                            action = VineService.ACTION_START
                        },
                    )
                }
                withTimeoutOrNull(4_000L) {
                    VineService.foregroundReady.first { it }
                } == true
            } catch (e: RuntimeException) {
                android.util.Log.e("VineOS", "Could not start foreground service", e)
                false
            }
            if (!foregroundStarted) {
                android.util.Log.e("VineOS", "Service did not become foreground promptly")
                flow.value = VMStatus.ERROR
                context.stopService(Intent(context, VineService::class.java))
                return@launch
            }

            val handle = VineRuntime.startInstance("""
if manager_text.count(before_start) != 1:
    raise RuntimeError("Unexpected upstream VM foreground-service startup")
manager_text = manager_text.replace(before_start, after_start, 1)
manager.write_text(manager_text, encoding="utf-8")

# Android debug APKs built on different GitHub runners have different signing keys.
# Give the fixed test build its own package name so the user does NOT have to
# uninstall the existing app and erase an imported 654 MB ROM to try the fix.
gradle = project / "app/build.gradle.kts"
gradle_text = gradle.read_text(encoding="utf-8")
old_suffix = 'applicationIdSuffix = ".debug"'
new_suffix = 'applicationIdSuffix = ".debug.fgsfix.diag3"'
if gradle_text.count(old_suffix) != 1:
    raise RuntimeError("Unexpected upstream debug applicationIdSuffix")
gradle.write_text(gradle_text.replace(old_suffix, new_suffix), encoding="utf-8")
strings_file = project / "app/src/main/res/values/strings.xml"
strings_text = strings_file.read_text(encoding="utf-8")
if strings_text.count('<string name="app_name">VineOS</string>') != 1:
    raise RuntimeError("Unexpected upstream app label")
strings_file.write_text(
    strings_text.replace(
        '<string name="app_name">VineOS</string>',
        '<string name="app_name">VineOS Diagnostics 3</string>'
    ), encoding="utf-8"
)

print("VineOS alpha patched: ROM provisioning + Android 16 FGS start + independent test install")
