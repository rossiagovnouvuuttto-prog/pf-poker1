package com.hexadecinull.vineos.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hexadecinull.vineos.domain.RootlessQemuEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Explicitly Linux-only proof of concept.
 * No fake Android home screen, no false guest ROOT claim, no user APK install.
 */
@Composable
fun RootlessQemuDemoScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { RootlessQemuEngine(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Ready to test the real ARM64 QEMU engine") }
    var launching by remember { mutableStateOf(false) }
    var console by remember { mutableStateOf("No guest output yet") }
    var error by remember { mutableStateOf("") }
    var launched by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { engine.stopImmediately() }
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (launched) {
                console = engine.serialLog()
                error = engine.errorLog()
                if (!engine.isRunning() && !launching) {
                    status = "QEMU process exited (see stderr/serial below)"
                    launched = false
                }
            }
            delay(750)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Rootless QEMU — Linux boot test", style = MaterialTheme.typography.titleLarge)
        Text(
            "This test boots a Linux kernel inside a QEMU ARM64 virtual machine " +
                "without ROOT on your tablet. It is NOT Android 9 and cannot " +
                "install APKs, Magisk or run Standoff 2.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("QEMU binary: " + if (engine.isEngineInstalled()) "Bundled" else "Missing")
        Text("Status: $status")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                enabled = !launching && !engine.isRunning() && engine.isEngineInstalled(),
                onClick = {
                    scope.launch {
                        launching = true
                        status = "Preparing Linux test kernel and initramfs..."
                        val guest = engine.prepareLinuxTestGuest()
                        if (guest.isFailure) {
                            status = "Guest preparation failed: ${guest.exceptionOrNull()?.message}"
                            launching = false
                            return@launch
                        }
                        status = "Launching unprivileged ARM64 emulation..."
                        val started = engine.start(guest.getOrThrow())
                        status = if (started.isSuccess) {
                            launched = true
                            "QEMU process started. Serial output will appear below."
                        } else {
                            "QEMU failed to start: ${started.exceptionOrNull()?.message}"
                        }
                        launching = false
                    }
                },
            ) { Text("Boot Linux test VM") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        engine.stop()
                        launched = false
                        status = "Stopped"
                    }
                },
                enabled = engine.isRunning(),
            ) { Text("Stop") }
        }
        if (launching) CircularProgressIndicator()
        OutlinedButton(onClick = onBack) { Text("Back to Settings") }
        HorizontalDivider()
        Text("Guest serial console", style = MaterialTheme.typography.titleMedium)
        Text(
            console.takeLast(7000),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        HorizontalDivider()
        Text("QEMU stderr", style = MaterialTheme.typography.titleMedium)
        Text(
            error.takeLast(3000),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
