// AtomicObjectFile.kt

package com.mochyy.mochymusic.utils

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.ObjectOutputStream

/**
 * Writes a serializable object so that a process killed mid-write cannot destroy the copy already on
 * disk: the object goes to a scratch file that only takes the real one's place once it is whole and
 * has reached the storage.
 *
 * Written straight into the target (which is what `File.outputStream()` does — it truncates first),
 * a save cut short left a file that threw on the next start, and the read side treated that as "the
 * saved session is gone".
 */
object AtomicObjectFile {

    fun write(target: File, value: Any) {
        val scratch = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(scratch).use { file ->
                val objects = ObjectOutputStream(file)
                objects.writeObject(value)
                // flushed and synced while both streams are still open: closing the object stream
                // closes the file stream under it, and a sync on a closed descriptor throws, which
                // would take the whole save with it
                objects.flush()
                file.fd.sync()
            }
            if (!scratch.renameTo(target)) {
                // a rename over an existing file is refused on some volumes
                target.delete()
                if (!scratch.renameTo(target)) throw IOException("could not replace ${target.name}")
            }
        } finally {
            scratch.delete()
        }
    }
}
