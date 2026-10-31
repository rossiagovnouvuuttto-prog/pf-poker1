package com.hexadecinull.vineos.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.hexadecinull.vineos.domain.QemuRfbFrameClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.Bitmap

/**
 * A real read-only-first diagnostic VNC frame preview.
 *
 * Unlike a placeholder black Compose screen, this shows actual decoded
 * QEMU framebuffer bytes and errors if there is no VNC/guest graphics.
 * The guest must render an Android HOME frame; the preview cannot create it.
 */
@Composable
fun QemuVncPreview(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val client = remember { QemuRfbFrameClient(5902) }
    val scope = rememberCoroutineScope()
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var status by remember { mutableStateOf("VNC display test not started") }

    DisposableEffect(client) { onDispose { client.close() } }

    LaunchedEffect(enabled) {
        if (!enabled) {
            status = "Start the QEMU guest to test actual video output"
            return@LaunchedEffect
        }
        status = "Connecting to QEMU VNC on 127.0.0.1:5902..."
        withContext(Dispatchers.IO) {
            // Port may not open until the VM finishes initializing.
            var connected = false
            repeat(12) {
                if (connected) return@repeat
                try {
                    client.connect()
                    connected = true
                } catch (e: Exception) {
                    delay(1500)
                }
            }
            if (!connected) {
                withContext(Dispatchers.Main) {
                    status = "VNC unavailable. QEMU might not support VNC or may have exited."
                }
            } else {
                withContext(Dispatchers.Main) {
                    status = "Connected to QEMU framebuffer (not yet proof of Android desktop)"
                }
                try {
                    client.receive { bitmap ->
                        scope.launch(Dispatchers.Main) {
                            frame = bitmap
                            status = "Receiving real ${bitmap.width}×${bitmap.height} QEMU frames"
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        status = "VNC stopped: ${e.message}"
                    }
                }
            }
        }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Actual QEMU display", style = MaterialTheme.typography.titleMedium)
        Text(
            "Experimental preview. A connected VNC stream does not mean Android 9 " +
                "has reached its home screen or GPU acceleration.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(status, style = MaterialTheme.typography.bodySmall)
        frame?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Live framebuffer from ARM64 QEMU guest",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth()
                    .widthIn(max = 950.dp)
                    .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    .pointerInput(bmp.width, bmp.height) {
                        detectTapGestures { tap ->
                            // For the diagnostic, a tap is the left-button
                            // press/release at the scaled QEMU framebuffer
                            // pixel position. Multi-touch needs separate work.
                            val x = (tap.x / size.width * bmp.width).toInt()
                            val y = (tap.y / size.height * bmp.height).toInt()
                            scope.launch(Dispatchers.IO) {
                                runCatching {
                                    client.pointer(x, y, true)
                                    client.pointer(x, y, false)
                                }
                            }
                        }
                    },
            )
        }
    }
}
