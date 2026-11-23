// a zip module's pictures and fonts, unpacked into the app's own files so its look works offline

package com.mochyy.mochymusic.modules.skin

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

internal object SkinFiles {

    fun folderFor(context: Context, moduleId: String): File =
        File(context.filesDir, "module_files/" + moduleId.replace(Regex("[^a-z0-9._-]"), "-"))

    /** unpacks the pictures and fonts under [root], the manifest's folder in the zip; null when nothing was kept */
    fun unpack(context: Context, uri: Uri, moduleId: String, root: String): File? {
        val target = folderFor(context, moduleId)
        // the new files land beside the old folder and only replace it once every one of them is in, so a zip that
        // fails halfway (a link that went away, a truncated download) leaves the look already installed working
        val staging = File(target.parentFile, target.name + ".incoming").apply { deleteRecursively(); mkdirs() }
        val stagingPath = staging.canonicalPath + File.separator
        var total = 0L
        var count = 0
        try {
            context.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (count < MaxFiles && total < MaxTotalBytes) {
                        val entry = zip.nextEntry ?: break
                        val relative = entry.name.trimStart('/').takeIf { !entry.isDirectory && it.startsWith(root) }?.removePrefix(root) ?: continue
                        if (!keeps(relative)) continue
                        val file = File(staging, relative)
                        if (!file.canonicalPath.startsWith(stagingPath)) continue
                        file.parentFile?.mkdirs()
                        val written = copyCapped(zip, file, minOf(MaxFileBytes, MaxTotalBytes - total))
                        if (written < 0) file.delete() else {
                            total += written
                            count++
                        }
                    }
                }
            }
        } catch (error: Exception) {
            // nothing was taken from the old folder yet, so it stays exactly as it was
            staging.deleteRecursively()
            return null
        }
        if (count == 0) {
            staging.deleteRecursively()
            return null
        }
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.copyRecursively(target, overwrite = true)
            staging.deleteRecursively()
        }
        return target.takeIf { it.isDirectory }
    }

    fun delete(context: Context, moduleId: String) {
        folderFor(context, moduleId).deleteRecursively()
    }

    // pictures, fonts and glass shaders only, and none of the hidden copies macos tucks into a zip
    private fun keeps(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it.isEmpty() || it == ".." || it == "__MACOSX" || it.startsWith(".") }) return false
        return path.substringAfterLast('.', "").lowercase() in Kinds
    }

    // the entry's bytes into [file], or -1 once it passes [limit] so a huge or lying entry can't fill the phone
    private fun copyCapped(zip: ZipInputStream, file: File, limit: Long): Long {
        var written = 0L
        val buffer = ByteArray(16 * 1024)
        file.outputStream().use { out ->
            while (true) {
                val read = zip.read(buffer)
                if (read < 0) break
                written += read
                if (written > limit) return -1
                out.write(buffer, 0, read)
            }
        }
        return written
    }

    private val Kinds = setOf("png", "jpg", "jpeg", "webp", "gif", "ttf", "otf", "agsl", "sksl")
    private const val MaxFiles = 200
    private const val MaxFileBytes = 8L * 1024 * 1024
    private const val MaxTotalBytes = 30L * 1024 * 1024
}
