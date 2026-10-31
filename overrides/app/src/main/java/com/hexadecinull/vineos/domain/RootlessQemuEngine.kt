package com.hexadecinull.vineos.domain

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
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
                require(kernel.isFile && kernel.length() in 1024..(128L * 1024 * 1024)) {
                    "Missing or invalid ARM64 guest kernel"
                }
                require(initramfs.isFile && initramfs.length() in 1024..(256L * 1024 * 1024)) {
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
                    "-append", "console=ttyAMA0",
                    "-nographic",
                    "-serial", "file:${bootLog.absolutePath}",
                    "-monitor", "none",
                    "-nic", "user,model=virtio-net-pci",
                    "-no-reboot",
                )
                // ProcessBuilder argv is passed directly, not through a shell.
                val pb = ProcessBuilder(argv)
                    .directory(privateBootDir)
                    .redirectOutput(File(privateBootDir, "qemu-stdout.log"))
                    .redirectError(errorLog)
                pb.environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir
                val p = pb.start()
                child = p
                Log.i("VineOS-QEMU", "Started experimental software-emulation PID ${p.pid()}")
                bootLog
            }
        }

    fun isRunning(): Boolean = child?.isAlive == true

    suspend fun stop() = withContext(Dispatchers.IO) {
        child?.let { p ->
            if (p.isAlive) {
                p.destroy()
                if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
            }
        }
        child = null
    }

    fun serialLog(): String {
        val file = File(context.filesDir, "qemu-guest/qemu-serial.log")
        if (!file.isFile) return "No serial boot log yet"
        val size = file.length()
        // Bound memory usage even if Linux kernel writes many MB of serial logs.
        return file.inputStream().buffered().use { input ->
            input.skip((size - 32768).coerceAtLeast(0))
            input.readBytes().toString(Charsets.UTF_8)
        }
    }
}
