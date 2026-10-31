package com.hexadecinull.vineos.domain

import com.hexadecinull.vineos.data.models.ROMImage
import com.hexadecinull.vineos.native.VineRuntime
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extract a real, bootable image from a locally downloaded .vrom archive.
 *
 * The alpha runtime previously created empty instance folders but never installed
 * the selected ROM. This class fixes that gap; it does NOT implement VM boot,
 * guest root, Binder isolation or GPU passthrough.
 */
@Singleton
class GuestImageProvisioner @Inject constructor() {
    suspend fun provision(rom: ROMImage, instanceId: String, storageMb: Int): String =
        withContext(Dispatchers.IO) {
            require(UUID.fromString(instanceId).toString() == instanceId) {
                "Invalid instance identifier"
            }
            require(rom.apiLevel >= 28) { "This build needs an Android 9+ guest image (API 28+)." }
            val romFile = rom.localPath?.let(::File)
                ?: error("Download or import an Android 9+ .vrom before creating an instance.")
            require(romFile.isFile) { "Guest ROM file does not exist: ${romFile.name}" }

            val instancePath = VineRuntime.createInstance(instanceId, romFile.absolutePath, storageMb)
                ?: error("VineOS could not create the instance directory.")
            val targetDir = File(instancePath)
            val target = File(targetDir, "rootfs.img")
            val pending = File(targetDir, "rootfs.img.part")
            try {
                ZipFile(romFile).use { zip ->
                    val image = zip.getEntry("rootfs.img")
                        ?: error("ROM archive has no rootfs.img. The Android 9 image must be a complete Linux root filesystem, not a system.img from a phone.")
                    require(!image.isDirectory && image.size > 4096L) {
                        "rootfs.img is empty or invalid."
                    }
                    require(image.size <= MAX_IMAGE_BYTES) { "Image is too large to import." }
                    require(targetDir.usableSpace > image.size + RESERVE_BYTES) {
                        "Not enough free storage to install the ROM."
                    }

                    val checksum = CRC32()
                    var copied = 0L
                    zip.getInputStream(image).buffered(256 * 1024).use { input ->
                        FileOutputStream(pending).buffered(256 * 1024).use { output ->
                            val buffer = ByteArray(256 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count == -1) break
                                copied += count
                                require(copied <= MAX_IMAGE_BYTES && copied <= image.size) {
                                    "Guest image expanded beyond its declared size."
                                }
                                checksum.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    require(copied == image.size && checksum.value == image.crc) {
                        "Guest image failed ZIP integrity check."
                    }
                    require(isSupportedFilesystem(pending)) {
                        "rootfs.img must be an ext4 or squashfs filesystem image."
                    }
                    require(pending.renameTo(target)) { "Could not finalize rootfs.img." }
                }
                instancePath
            } catch (e: Exception) {
                pending.delete()
                target.delete()
                // Only delete the newly allocated directory, never arbitrary user paths.
                if (targetDir.name == instanceId) targetDir.deleteRecursively()
                throw e
            }
        }

    private fun isSupportedFilesystem(image: File): Boolean {
        image.inputStream().use { input ->
            val magic = ByteArray(4)
            if (input.read(magic) == 4 && magic.contentEquals(byteArrayOf(0x68, 0x73, 0x71, 0x73))) {
                return true // squashfs: hsqs
            }
        }
        image.inputStream().use { input ->
            val skipped = input.skip(1080)
            if (skipped != 1080L) return false
            return input.read() == 0x53 && input.read() == 0xEF // ext4 superblock magic
        }
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 16L * 1024 * 1024 * 1024
        private const val RESERVE_BYTES = 256L * 1024 * 1024
    }
}
