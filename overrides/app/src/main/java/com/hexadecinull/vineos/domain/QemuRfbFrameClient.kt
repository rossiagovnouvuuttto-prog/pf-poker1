package com.hexadecinull.vineos.domain

import android.graphics.Bitmap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Minimal RFB 3.8 framebuffer client for QEMU VNC on 127.0.0.1 only.
 *
 * This component is NOT a graphic compositor or a ROOT solution. It only
 * transports screen pixels from a guest that is ALREADY rendering a real
 * framebuffer. QEMU must be built with --enable-vnc (Podroid release was not).
 *
 * Security: RFB "None" authentication is suitable only for a disposable
 * local boot diagnostic. Do not sign in to accounts in this test guest.
 * Before shipping a normal VM, switch to authenticated/encrypted transport.
 */
class QemuRfbFrameClient(
    private val port: Int = 5902,
) : AutoCloseable {
    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null
    private val closed = AtomicBoolean(false)
    private var width = 0
    private var height = 0

    fun connect() {
        require(port in 5900..5999)
        check(socket == null) { "Already connected" }
        val s = Socket()
        try {
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            s.soTimeout = 15000
            s.tcpNoDelay = true
            val i = DataInputStream(BufferedInputStream(s.getInputStream()))
            val o = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            val helloBytes = ByteArray(12)
            i.readFully(helloBytes)
            val hello = String(helloBytes, StandardCharsets.US_ASCII)
            require(hello.startsWith("RFB 003.")) { "Unsupported RFB protocol: $hello" }
            val version = hello.substring(8, 11).toIntOrNull() ?: 0
            require(version >= 8) { "This test requires RFB 3.8" }
            o.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII))
            o.flush()
            val count = i.readUnsignedByte()
            if (count == 0) {
                throw IllegalStateException("VNC security methods unavailable: ${readReason(i)}")
            }
            val methods = ByteArray(count)
            i.readFully(methods)
            require(methods.contains(1.toByte())) {
                "No-auth loopback mode unavailable. Secure VNC is not supported by this diagnostic."
            }
            o.writeByte(1) // None - test guest, loopback only
            o.flush()
            val auth = i.readInt()
            check(auth == 0) { "VNC authentication rejected: ${readReason(i)}" }

            o.writeByte(1) // Shared desktop
            o.flush()
            width = i.readUnsignedShort()
            height = i.readUnsignedShort()
            require(width in 16..4096 && height in 16..4096) {
                "Unsafe framebuffer dimensions: ${width}x${height}"
            }
            val defaultPixelFormat = ByteArray(16)
            i.readFully(defaultPixelFormat)
            val nameLength = i.readInt()
            require(nameLength in 0..4096)
            val name = ByteArray(nameLength)
            i.readFully(name)

            // Request 32-bit little-endian XRGB (B, G, R, unused on wire).
            // Client SetPixelFormat: type, 3 pad, 16-byte pixel format.
            o.writeByte(0)
            repeat(3) { o.writeByte(0) }
            o.writeByte(32) // bpp
            o.writeByte(24) // depth
            o.writeByte(0)  // little endian
            o.writeByte(1)  // true color
            o.writeShort(255)
            o.writeShort(255)
            o.writeShort(255)
            o.writeByte(16) // red shift
            o.writeByte(8)  // green shift
            o.writeByte(0)  // blue shift
            repeat(3) { o.writeByte(0) }
            // SetEncodings: only RAW (0) - deliberately simple + auditable.
            o.writeByte(2)
            o.writeByte(0)
            o.writeShort(1)
            o.writeInt(0)
            o.flush()
            socket = s
            input = i
            output = o
        } catch (t: Throwable) {
            s.close()
            throw t
        }
    }

    /**
     * Blocks on the network reading real framebuffer updates. Use on
     * Dispatchers.IO only. onFrame should marshal to Android main thread.
     */
    fun receive(onFrame: (Bitmap) -> Unit) {
        val i = checkNotNull(input)
        val o = checkNotNull(output)
        val pixels = IntArray(width * height) { -0x1000000 }
        var first = true
        while (!closed.get()) {
            o.writeByte(3) // FramebufferUpdateRequest
            o.writeByte(if (first) 0 else 1)
            o.writeShort(0)
            o.writeShort(0)
            o.writeShort(width)
            o.writeShort(height)
            o.flush()
            first = false
            val type = i.readUnsignedByte()
            when (type) {
                0 -> {
                    i.readUnsignedByte() // pad
                    val rects = i.readUnsignedShort()
                    require(rects in 0..512) { "Too many RFB rectangles" }
                    for (r in 0 until rects) {
                        val x = i.readUnsignedShort()
                        val y = i.readUnsignedShort()
                        val w = i.readUnsignedShort()
                        val h = i.readUnsignedShort()
                        val encoding = i.readInt()
                        require(encoding == 0 && w > 0 && h > 0 &&
                            x + w <= width && y + h <= height) {
                            "Unsupported or invalid VNC encoding/rectangle: $encoding"
                        }
                        val line = ByteArray(w * 4)
                        for (row in 0 until h) {
                            i.readFully(line)
                            for (col in 0 until w) {
                                val k = col * 4
                                val b = line[k].toInt() and 0xff
                                val g = line[k + 1].toInt() and 0xff
                                val red = line[k + 2].toInt() and 0xff
                                pixels[(y + row) * width + x + col] =
                                    -0x1000000 or (red shl 16) or (g shl 8) or b
                            }
                        }
                    }
                    if (rects > 0) onFrame(
                        Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
                    )
                }
                2 -> Unit // Bell
                3 -> { // ServerCutText, ignored (avoid large allocations).
                    repeat(3) { i.readUnsignedByte() }
                    val n = i.readInt()
                    require(n in 0..1_048_576)
                    val buffer = ByteArray(4096)
                    var remaining = n
                    while (remaining > 0) {
                        val bytes = minOf(buffer.size, remaining)
                        i.readFully(buffer, 0, bytes)
                        remaining -= bytes
                    }
                }
                else -> throw IllegalStateException("Unsupported RFB server message $type")
            }
        }
    }

    /** RFB pointer event coordinates in the guest framebuffer. */
    @Synchronized
    fun pointer(x: Int, y: Int, pressed: Boolean) {
        val o = output ?: return
        if (closed.get()) return
        o.writeByte(5)
        o.writeByte(if (pressed) 1 else 0)
        o.writeShort(x.coerceIn(0, width - 1))
        o.writeShort(y.coerceIn(0, height - 1))
        o.flush()
    }

    private fun readReason(i: DataInputStream): String {
        val len = i.readInt()
        if (len !in 0..2048) return "server error"
        val bytes = ByteArray(len)
        i.readFully(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    override fun close() {
        closed.set(true)
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
