package com.hexadecinull.vineos.domain

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Rootless QEMU/TCG backend prototype.
 *
 * Different from the alpha container backend: QEMU emulates the whole ARM64
 * machine in the app's own UID. It never tries /dev/loop-control, mount(),
 * unshare() or host CAP_SYS_ADMIN.
 *
 * This class builds and starts a headless QEMU ARM64 Linux VM. Android 9 boot
 * images, video (VNC/virtio-gpu), guest ROOT, Binder and input integration
 * are NOT implemented by this prototype.
 *
 * The QEMU ARM64 executable is not yet bundled. It must be built for Android
 * ARM64, installed by Android's package manager in nativeLibraryDir as an
 * executable ELF named libqemu-system-aarch64.so, with the required licenses.
 */
class RootlessQemuEngine(private val context: Context) {
    data class Config(val ramMb: Int = 2048, val cores: Int = 2)
    private var child: Process? = null

    val executable: File
        get() = File(context.applicationInfo.nativeLibraryDir, "libqemu-system-aarch64.so")

    fun isEngineInstalled(): Boolean = executable.isFile && executable.canExecute()

    /**
     * Copies the small Alpine test kernel/initramfs bundled into THIS test APK.
     * Stays within private app storage; never requires SAF or root.
     */
    suspend fun prepareLinuxTestGuest(): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val folder = File(context.filesDir, "qemu-guest")
            check(folder.isDirectory || folder.mkdirs()) { "Cannot create private guest folder" }
            val kernel = File(folder, "kernel")
            val initramfs = File(folder, "initramfs")
            listOf("kernel" to kernel, "initramfs" to initramfs).forEach { (name, out) ->
                if (!out.isFile || out.length() < 1_000_000L) {
                    val pending = File(folder, "$name.part")
                    try {
                        context.assets.open("qemu-linux-test/$name").use { input ->
                            FileOutputStream(pending).use { output ->
                                input.copyTo(output, 128 * 1024)
                            }
                        }
                        check(pending.length() in 1_000_000L..(128L * 1024 * 1024)) {
                            "Invalid bundled Linux test file: $name"
                        }
                        check(pending.renameTo(out)) { "Could not stage Linux test file: $name" }
                    } finally {
                        pending.delete()
                    }
                }
            }
            folder
        }
    }


    /**
     * Boot bundle consists of kernel + initramfs under app-private storage.
     * A Redroid .vrom rootfs image by itself is NOT a QEMU boot bundle.
     */
    suspend fun start(bootDir: File, config: Config = Config()): Result<File> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(child?.isAlive != true) { "QEMU VM is already running" }
                require(config.ramMb in 512..4096) { "RAM outside permitted 512..4096 MB" }
                require(config.cores in 1..4) { "CPU cores outside permitted range" }

                val privateBootDir = File(context.filesDir, "qemu-guest").canonicalFile
                require(bootDir.canonicalFile == privateBootDir) {
                    "QEMU boot images must be staged in app-private qemu-guest folder"
                }
                require(isEngineInstalled()) {
                    "Rootless QEMU binary not bundled. This build is a development prototype, not a bootable Android 9 VM."
                }
                val kernel = File(privateBootDir, "kernel")
                val initramfs = File(privateBootDir, "initramfs")
                require(kernel.isFile && kernel.length() >= 1024L && kernel.length() <= (128L * 1024 * 1024)) {
                    "Missing or invalid ARM64 guest kernel"
                }
                require(initramfs.isFile && initramfs.length() >= 1024L && initramfs.length() <= (256L * 1024 * 1024)) {
                    "Missing or invalid ARM64 initramfs"
                }
                val bootLog = File(privateBootDir, "qemu-serial.log")
                val errorLog = File(privateBootDir, "qemu-stderr.log")
                // No shell or external file paths: user-supplied ROM labels cannot
                // inject flags, network shares or host executable arguments.
                val argv = listOf(
                    executable.absolutePath,
                    "-machine", "virt",
                    "-accel", "tcg,thread=multi",
                    "-cpu", "cortex-a57",
                    "-smp", config.cores.toString(),
                    "-m", config.ramMb.toString(),
                    "-kernel", kernel.absolutePath,
                    "-initrd", initramfs.absolutePath,
                    "-append", "console=ttyAMA0 rdinit=/init",
                    "-display", "none",
                    "-serial", "file:${bootLog.absolutePath}",
                    "-monitor", "none",
                    "-nic", "none",
                    "-no-reboot",
                )
                // ProcessBuilder argv is passed directly, not through a shell.
                val pb = ProcessBuilder(argv)
                    .directory(privateBootDir)
                    .redirectOutput(File(privateBootDir, "qemu-stdout.log"))
                    .redirectError(errorLog)
                pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                // Never report a stale previous boot trace as success.
                bootLog.delete()
                errorLog.delete()
                val p = pb.start()
                child = p
                Log.i("VineOS-QEMU", "Started experimental software-emulation process")
                bootLog
            }
        }

    fun isRunning(): Boolean = child?.isAlive == true

    /** Stop process on leaving the test screen without waiting on the main thread. */
    fun stopImmediately() {
        child?.destroy()
        child = null
    }

    /** Read last bounded lines from QEMU stderr for on-device troubleshooting. */
    fun errorLog(): String = readTail(File(context.filesDir, "qemu-guest/qemu-stderr.log"))

    fun serialLog(): String = readTail(File(context.filesDir, "qemu-guest/qemu-serial.log"))

    private fun readTail(file: File): String {
        if (!file.isFile) return "No log yet"
        return file.inputStream().buffered().use { input ->
            val size = file.length()
            var remaining = (size - 16384L).coerceAtLeast(0L)
            while (remaining > 0L) {
                val skipped = input.skip(remaining)
                if (skipped <= 0L) break
                remaining -= skipped
            }
            input.readBytes().toString(Charsets.UTF_8)
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        child?.let { p ->
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        child = null
    }
}
