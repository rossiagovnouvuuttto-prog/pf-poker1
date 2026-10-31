#!/usr/bin/env python3
"""Show guest boot errors in VineOS instead of a featureless black screen.

Applied to the pinned upstream source during the GitHub Actions build. This
does not itself grant host CAP_SYS_ADMIN or implement GPU/guest boot.
"""
import argparse
from pathlib import Path

argp=argparse.ArgumentParser()
argp.add_argument("--project",type=Path,default=Path("upstream"))
project=argp.parse_args().project
base=project/"app/src/main/java/com/hexadecinull/vineos"

def replace_once(original, before, after, label):
    if original.count(before)!=1:
        raise RuntimeError(f"{label}: expected 1 source match, got {original.count(before)}")
    return original.replace(before,after,1)

# Keep the root-cause message where the failed native startup happens.
manager=base/"domain/VineVMManager.kt"
s=manager.read_text(encoding="utf-8")
s=replace_once(s,
    "    private val statusFlows = mutableMapOf<String, MutableStateFlow<VMStatus>>()",
    """    private val statusFlows = mutableMapOf<String, MutableStateFlow<VMStatus>>()
    private val startupErrors = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun getStartupError(instanceId: String): String? = startupErrors[instanceId]

    // Current runtime mounts ext4 loop images and creates Linux namespaces.
    // These operations require CAP_SYS_ADMIN in the host app process.
    private fun hasHostSysAdmin(): Boolean? = runCatching {
        val capHex = java.io.File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("CapEff:") }
                ?.substringAfter(':')?.trim()
        } ?: return@runCatching null
        (java.lang.Long.parseUnsignedLong(capHex, 16) and (1L shl 21)) != 0L
    }.getOrNull()""",
    "VM manager maps")
s=replace_once(s,
    """            flow.value = VMStatus.BOOTING

""",
    """            flow.value = VMStatus.BOOTING
            startupErrors.remove(instance.id)
            if (hasHostSysAdmin() == false) {
                startupErrors[instance.id] =
                    "This VineOS engine cannot start a guest on this host: " +
                    "the VineOS app process has no CAP_SYS_ADMIN. " +
                    "Mounting the Android 9 rootfs as a loop device and creating " +
                    "the required Linux namespaces need host privileges. " +
                    "Magisk installed INSIDE the guest cannot grant these. " +
                    "The guest has not been booted."
                flow.value = VMStatus.ERROR
                return@launch
            }

""",
    "VM manager start")
s=replace_once(s,
    """            if (handle == 0L) {
                flow.value = VMStatus.ERROR""",
    """            if (handle == 0L) {
                startupErrors[instance.id] =
                    "Guest could not start. Native engine could not mount or launch " +
                    "the Android 9 filesystem. This usually requires host access to " +
                    "/dev/loop-control and CAP_SYS_ADMIN, which stock HyperOS 3 does not provide. " +
                    "Adding APK files (Magisk, Shizuku or Aurora Store) will not fix this."
                flow.value = VMStatus.ERROR""",
    "native startup error")
manager.write_text(s,encoding="utf-8")

# Surface errors to Compose state.
model=base/"ui/viewmodel/VMDisplayViewModel.kt"
s=model.read_text(encoding="utf-8")
s=replace_once(s,
    """    var isBooting by mutableStateOf(false)
        private set""",
    """    var isBooting by mutableStateOf(false)
        private set
    var bootError by mutableStateOf<String?>(null)
        private set""",
    "Display viewmodel state")
s=replace_once(s,
    """                isBooting = status == VMStatus.BOOTING""",
    """                isBooting = status == VMStatus.BOOTING
                // The initial StateFlow value is STOPPED: it is emitted
                // before launchInstance() gets scheduled on Dispatchers.IO.
                // Do not misreport it as a boot failure.
                if (status == VMStatus.BOOTING || status == VMStatus.RUNNING) {
                    launchObserved = true
                }
                if (status == VMStatus.ERROR) {
                    bootError = vmManager.getStartupError(id)
                        ?: "Android 9 runtime failed to start."
                } else if (status == VMStatus.STOPPED && launchObserved && bootError == null) {
                    bootError = "Guest process stopped after startup; Android 9 display never appeared."
                }""",
    "Display viewmodel collection")
s=replace_once(s,
    """        instanceId = null
        instanceHandle = 0L""",
    """        instanceId = null
        instanceHandle = 0L
        displayWatchdog?.cancel()
        displayWatchdog = null
        bootError = null""",
    "Display viewmodel detach")
s=replace_once(s,
    "import kotlinx.coroutines.Job",
    "import kotlinx.coroutines.Job\\nimport kotlinx.coroutines.delay",
    "Display watchdog imports")
s=s.replace("import kotlinx.coroutines.Job\\nimport", "import kotlinx.coroutines.Job\nimport")
s=replace_once(s,
    """    private var statusJob: Job? = null""",
    """    private var statusJob: Job? = null
    private var displayWatchdog: Job? = null""",
    "Display watchdog job")
s=replace_once(s,
    """    fun attach(id: String) {
        instanceId = id
        statusJob = viewModelScope.launch {""",
    """    fun attach(id: String) {
        instanceId = id
        var launchObserved = false
        displayWatchdog?.cancel()
        displayWatchdog = viewModelScope.launch {
            // A VM process can exist while Android init / the guest display
            // never actually starts. Detect this and avoid a forever-black UI.
            delay(20_000L)
            if (bootError == null && launchObserved) {
                // Even a valid /dev/graphics/fb0 fd can contain only zeros.
                // There is currently no verified frame-presentation callback.
                bootError = "After 20 seconds VineOS still cannot confirm a " +
                    "working Android 9 desktop. This black view is only the " +
                    "VineOS display surface, not an Android launcher. " +
                    "On an unrooted HyperOS device the native VM engine " +
                    "usually cannot access loop devices or mount a guest " +
                    "filesystem (CAP_SYS_ADMIN required). Aurora Store, " +
                    "Magisk and Shizuku APKs cannot fix guest boot."
            }
        }
        statusJob = viewModelScope.launch {""",
    "Display watchdog launch")
model.write_text(s,encoding="utf-8")

# Show a visible diagnostic overlay in the black guest surface.
ui=base/"ui/screens/VMDisplayScreen.kt"
s=ui.read_text(encoding="utf-8")
s=replace_once(s,
    "import androidx.compose.foundation.layout.Box",
    """import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth""",
    "Display UI imports")
s=replace_once(s,
    "import androidx.compose.material3.FloatingActionButton",
    """import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Button""",
    "Display UI button")
marker="""        // Booting overlay
        if (vm.isBooting) {"""
replacement="""        // A black SurfaceView is NOT a minimal Android desktop.
        // Tell the user if there is no working guest framebuffer.
        val error = vm.bootError
        if (error != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
                    .padding(28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Android 9 display not verified",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                )
                Spacer(Modifier.size(16.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Spacer(Modifier.size(20.dp))
                Button(onClick = onBack) { Text("Back to Instances") }
            }
        }

        // Booting overlay
        if (vm.isBooting) {"""
s=replace_once(s,marker,replacement,"Display UI error overlay")
ui.write_text(s,encoding="utf-8")
print("VineOS watchdog fixed: no false success from framebuffer fd; persistent guest boot diagnostics")
