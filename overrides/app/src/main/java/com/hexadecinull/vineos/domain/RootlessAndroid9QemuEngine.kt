package com.hexadecinull.vineos.domain

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QEMU boot test with actual Android 9 files (Redroid .vrom).
 *
 * Real Android 9 /init can start in QEMU on a virtio ext4 filesystem.
 * NO graphical desktop, guest app ROOT, /dev/ashmem, GPU HAL, or boot
 * completion is claimed or verified by this prototype.
 *
 * QEMU runs in the host app UID, without host CAP_SYS_ADMIN.
 */
class RootlessAndroid9QemuEngine(private val context: Context) {
    private val base: File get() = File(context.filesDir, "qemu-android9")
    private val qemu: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")
    private var child: Process? = null

    fun binaryAvailable(): Boolean = qemu.isFile && qemu.canExecute()
    fun running(): Boolean = child?.isAlive == true

    /**
     * Import only a ZIP entry named rootfs.img. The selected ROM must already
     * contain a complete ext4 Redroid9 root filesystem; a phone system.img
     * or raw Android emulator image is not sufficient.
     */
    suspend fun importVromAndStart(uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            check(!running()) { "Stop the existing VM before importing a new ROM" }
            check(binaryAvailable()) { "Bundled QEMU binary missing" }
            check(base.isDirectory || base.mkdirs()) { "Cannot create Android9 VM storage" }

            stageAsset("qemu-android9/kernel", File(base, "kernel"), 2_000_000)
            stageAsset("qemu-android9/stage0.img", File(base, "stage0.img"), 1_000_000)

            val archive = File(base, "android9-import.vrom.part")
            val disk = File(base, "android9-rootfs.img")
            val tempDisk = File(base, "android9-rootfs.img.part")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(archive).use { output ->
                        val buf = ByteArray(1024 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            copied += n
                            check(copied <= MAX_VROM) { "The selected ROM is too large" }
                            output.write(buf, 0, n)
                        }
                    }
                } ?: error("Could not open the selected .vrom file")

                ZipFile(archive).use { zip ->
                    val entry = zip.getEntry("rootfs.img")
                        ?: error("Archive has no rootfs.img; select our Android 9 Redroid .vrom")
                    require(!entry.isDirectory && entry.size in 1024L..MAX_ROOTFS) {
                        "Invalid Android 9 root filesystem size"
                    }
                    require(base.usableSpace > entry.size + 256L * 1024 * 1024) {
                        "Not enough free storage for guest Android 9 disk"
                    }

                    zip.getEntry("manifest.json")?.let { meta ->
                        val manifest = zip.getInputStream(meta).bufferedReader().use { it.readText() }
                        if (manifest.contains("\"apiLevel\"")) {
                            val sdk = org.json.JSONObject(manifest).optInt("apiLevel", -1)
                            require(sdk == 28) { "Selected image is not Android 9 API28" }
                        }
                    }

                    val crc = CRC32()
                    var received = 0L
                    zip.getInputStream(entry).use { src ->
                        FileOutputStream(tempDisk).use { dst ->
                            val buf = ByteArray(1024 * 1024)
                            while (true) {
                                val n = src.read(buf)
                                if (n < 0) break
                                received += n
                                check(received <= entry.size && received <= MAX_ROOTFS) {
                                    "ROM expanded beyond its declared size"
                                }
                                crc.update(buf, 0, n)
                                dst.write(buf, 0, n)
                            }
                        }
                    }
                    require(received == entry.size && crc.value == entry.crc) {
                        "ROM failed archive integrity check"
                    }
                }
                RandomAccessFile(tempDisk, "r").use { raf ->
                    raf.seek(1080L)
                    check(raf.readUnsignedByte() == 0x53 && raf.readUnsignedByte() == 0xEF) {
                        "rootfs.img is not an ext4 filesystem"
                    }
                }
                check(!disk.exists() || disk.delete()) { "Cannot replace previous guest disk" }
                check(tempDisk.renameTo(disk)) { "Cannot finalize Android guest disk" }
            } finally {
                archive.delete()
                tempDisk.delete()
            }

            File(base, "serial.log").delete()
            File(base, "stderr.log").delete()
            val args = listOf(
                qemu.absolutePath,
                "-machine", "virt",
                "-accel", "tcg,thread=multi",
                "-cpu", "cortex-a57",
                "-smp", "2",
                "-m", "2048",
                "-kernel", File(base, "kernel").absolutePath,
                "-initrd", File(base, "stage0.img").absolutePath,
                "-append", "console=ttyAMA0 rdinit=/init androidboot.hardware=redroid androidboot.selinux=permissive selinux=0",
                "-drive", "if=none,file=${disk.absolutePath},format=raw,id=androiddisk",
                "-device", "virtio-blk-device,drive=androiddisk",
                "-display", "none",
                "-serial", "file:${File(base, "serial.log").absolutePath}",
                "-monitor", "none",
                "-nic", "none",
                "-no-reboot",
            )
            val proc = ProcessBuilder(args)
                .directory(base)
                .redirectOutput(File(base, "stdout.log"))
                .redirectError(File(base, "stderr.log"))
                .apply {
                    environment()["LD_LIBRARY_PATH"] =
                        context.applicationInfo.nativeLibraryDir
                }.start()
            child = proc
        }
    }

    private fun stageAsset(asset: String, target: File, minBytes: Long) {
        if (target.isFile && target.length() >= minBytes) return
        val temp = File(target.parentFile, target.name + ".part")
        try {
            context.assets.open(asset).use { source ->
                FileOutputStream(temp).use { out -> source.copyTo(out, 128 * 1024) }
            }
            check(temp.length() >= minBytes) { "Bundled boot component is invalid: $asset" }
            check(!target.exists() || target.delete()) { "Cannot replace $asset" }
            check(temp.renameTo(target)) { "Cannot stage $asset" }
        } finally {
            temp.delete()
        }
    }

    fun stop() {
        child?.destroy()
        child = null
    }

    fun log(): String = readTail(File(base, "serial.log"), 28_000)
    fun stderr(): String = readTail(File(base, "stderr.log"), 6_000)

    fun milestone(): String {
        val log = log()
        return when {
            log.contains("init: init second stage started!") ->
                "Android 9 second-stage init has started; GUI is not verified"
            log.contains("init: init first stage started!") ->
                "Android 9 first-stage init has started"
            log.contains("ANDROID9_STAGE0_HANDOFF_TO_ACTUAL_INIT") ->
                "Linux handed control to the real Android 9 /init"
            log.contains("ANDROID9_STAGE0_VERIFIED_ANDROID9_API28") ->
                "Android 9 disk mounted and API 28 verified"
            log.contains("ANDROID9_STAGE0_FAILED") -> "Guest boot failed; read the serial console"
            log.contains("ANDROID9_STAGE0_KERNEL_READY") -> "QEMU started the guest kernel"
            else -> "Waiting for guest kernel output"
        }
    }

    private fun readTail(file: File, limit: Int): String {
        if (!file.isFile) return "No output yet"
        RandomAccessFile(file, "r").use { raf ->
            val offset = (raf.length() - limit).coerceAtLeast(0L)
            raf.seek(offset)
            val bytes = ByteArray((raf.length() - offset).toInt())
            raf.readFully(bytes)
            return bytes.toString(Charsets.UTF_8)
        }
    }

    companion object {
        private const val MAX_VROM = 2L * 1024 * 1024 * 1024
        private const val MAX_ROOTFS = 6L * 1024 * 1024 * 1024
    }
}
