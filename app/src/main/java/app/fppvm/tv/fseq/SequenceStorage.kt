package app.fppvm.tv.fseq

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

/**
 * Works out where sequences should live.
 *
 * The TV's own `/data` had about a gigabyte free and a single 640x360 show is 158 MB, so a USB
 * stick is the difference between holding six shows and sixty. `getExternalFilesDirs` returns an
 * app-owned directory on *every* mounted volume — internal, SD, USB — and needs **no permission on
 * any API level**, which makes it the one route that works on API 28 and still survives scoped
 * storage. The cost is an unlovely path (`/Android/data/app.fppvm.tv/files/`) if you want to drop
 * files on the stick from a PC; the folder import exists for that.
 *
 * Reads look at every volume, writes go to one. That way pulling the stick loses the sequences on
 * it and nothing else.
 */
object SequenceStorage {

    private const val TAG = "FppStorage"
    const val SEQ_SUBDIR = "sequences"

    data class Volume(
        val dir: File,
        val removable: Boolean,
        val label: String,
        val freeBytes: Long,
        val totalBytes: Long
    ) {
        val usable: Boolean get() = dir.isDirectory || dir.mkdirs()
    }

    /**
     * Every writable sequence directory, internal first then removable.
     *
     * `getExternalFilesDirs` can return nulls for volumes that are present but not mounted, which
     * is why the filtering is not decorative.
     */
    fun volumes(context: Context): List<Volume> {
        val out = ArrayList<Volume>(3)

        // Internal app storage is always there and always writable.
        val internal = File(context.filesDir, SEQ_SUBDIR)
        out.add(volumeFor(internal, removable = false, label = "Internal"))

        try {
            context.getExternalFilesDirs(null).filterNotNull().forEach { base ->
                val dir = File(base, SEQ_SUBDIR)
                val removable = try {
                    Environment.isExternalStorageRemovable(base)
                } catch (t: Throwable) {
                    // Thrown for a volume that has been yanked between listing and asking.
                    false
                }
                val mounted = try {
                    Environment.getExternalStorageState(base) == Environment.MEDIA_MOUNTED
                } catch (t: Throwable) {
                    false
                }
                if (!mounted) return@forEach
                // The primary external volume on a TV is usually emulated internal storage, which
                // is the same physical space as filesDir and so not worth listing twice.
                if (!removable) return@forEach
                out.add(volumeFor(dir, removable = true, label = labelFor(base)))
            }
        } catch (t: Throwable) {
            Log.w(TAG, "could not enumerate external volumes", t)
        }
        return out
    }

    private fun volumeFor(dir: File, removable: Boolean, label: String): Volume {
        val free = try {
            dir.parentFile?.freeSpace ?: 0L
        } catch (t: Throwable) {
            0L
        }
        val total = try {
            dir.parentFile?.totalSpace ?: 0L
        } catch (t: Throwable) {
            0L
        }
        return Volume(dir, removable, label, free, total)
    }

    /** `/storage/1234-5678/Android/data/...` -> `1234-5678`, which is what the TV shows. */
    private fun labelFor(base: File): String {
        val parts = base.absolutePath.split('/')
        val i = parts.indexOf("storage")
        return if (i >= 0 && i + 1 < parts.size) "USB ${parts[i + 1]}" else "Removable"
    }

    /** True when a removable volume is mounted and usable. */
    fun removableAvailable(context: Context): Boolean =
        volumes(context).any { it.removable && it.usable }

    /**
     * Where new downloads and uploads should be written.
     *
     * Prefers a removable volume when [preferRemovable] is set and one is present, so plugging a
     * stick in is all it takes. Falls back to internal the moment it is pulled.
     */
    fun writeDir(context: Context, preferRemovable: Boolean): File {
        val vols = volumes(context)
        val pick = if (preferRemovable) {
            vols.firstOrNull { it.removable && it.usable } ?: vols.first()
        } else {
            vols.first()
        }
        if (!pick.dir.exists()) pick.dir.mkdirs()
        return pick.dir
    }

    /** Every directory worth searching when resolving a sequence by name. */
    fun searchDirs(context: Context): List<File> =
        volumes(context).map { it.dir }.filter { it.isDirectory || it.mkdirs() }
}
