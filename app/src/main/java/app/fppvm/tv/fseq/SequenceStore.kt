package app.fppvm.tv.fseq

import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local cache of the sequences this remote can play.
 *
 * FPP remotes each hold their own copy of every `.fseq`; MultiSync only carries timing and the
 * filename. Normally you push those copies out from xLights (FPP Connect) or the player's file
 * copy, which means an inbound HTTP API on every remote. This device pulls instead: when a sync
 * packet names a sequence we do not have, we fetch it from the master's own file API
 * (`GET /api/file/sequences/<name>`). That needs no configuration on the player and no upload
 * step — plug the TV in and it catches up by itself.
 */
class SequenceStore(
    /** Where new files are written. Swapped at runtime when a stick is plugged in or pulled. */
    @Volatile var dir: File,
    private val budgetBytes: Long = DEFAULT_BUDGET_BYTES,
    /**
     * Every directory to search when resolving a name. Reads span all volumes so a sequence that
     * lives on a stick is still found while new ones land wherever [dir] currently points.
     */
    @Volatile var searchDirs: List<File> = listOf(dir)
) {

    companion object {
        private const val TAG = "FppSeqStore"
        const val DEFAULT_BUDGET_BYTES = 1_073_741_824L // 1 GiB
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Sync packets carry the master's own path conventions and, in some setups, subdirectories.
         * Only the leaf name is meaningful to us, and anything that could climb out of the cache
         * directory is rejected outright.
         */
        fun sanitize(filename: String): String? {
            val leaf = filename.replace('\\', '/').substringAfterLast('/').trim()
            if (leaf.isEmpty() || leaf == "." || leaf == "..") return null
            // Spaces are ordinary in show filenames ("Wizards In Winter.fseq") and must
            // survive; separators, traversal and control characters must not.
            if (leaf.any { it.code < 0x20 || it == '/' }) return null
            return leaf
        }

        /**
         * Containers this device will try to play. Kept to what the hardware decoder handles —
         * the point of show media is that the SoC decodes it, not the CPU.
         */
        val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "m4v", "mov", "webm")
    }

    init {
        if (!dir.exists()) dir.mkdirs()
    }

    sealed class Result {
        data class Ready(val file: File) : Result()
        data class Fetching(val name: String) : Result()
        data class Missing(val name: String, val reason: String) : Result()
    }

    private val inFlight = HashSet<String>()

    fun localFile(filename: String): File? {
        val name = sanitize(filename) ?: return null
        for (d in dirsToSearch()) {
            val f = File(d, name)
            if (f.isFile && f.length() > 0) return f
        }
        return null
    }

    private fun dirsToSearch(): List<File> {
        val all = searchDirs
        return if (all.isEmpty()) listOf(dir) else all
    }

    /**
     * The video that goes with a sequence, matched by filename the way FPP pairs audio.
     *
     * `show.fseq` plus `show.mp4` means play both; the file being there *is* the instruction, so
     * there is no mode to set and nothing to keep in step with the media on disk.
     */
    fun pairedVideo(sequenceName: String): File? {
        val name = sanitize(sequenceName) ?: return null
        val stem = name.substringBeforeLast('.', name)
        for (d in dirsToSearch()) {
            for (ext in VIDEO_EXTENSIONS) {
                val f = File(d, "$stem.$ext")
                if (f.isFile && f.length() > 0) return f
            }
        }
        return null
    }

    /** True when this name is a video rather than a sequence. */
    fun isVideo(filename: String): Boolean =
        VIDEO_EXTENSIONS.any { filename.endsWith(".$it", ignoreCase = true) }

    /** Points writes at [next] and searches [all]. Safe to call while playing. */
    fun useDirectories(next: File, all: List<File>) {
        if (!next.exists()) next.mkdirs()
        dir = next
        searchDirs = all.ifEmpty { listOf(next) }
    }

    fun has(filename: String): Boolean = localFile(filename) != null

    fun listCached(): List<File> = dirsToSearch()
        .flatMap { it.listFiles()?.filter { f -> f.isFile && !f.name.endsWith(".part") && !f.name.endsWith(".upload") } ?: emptyList() }
        .distinctBy { it.name }
        .sortedBy { it.name }

    fun totalBytes(): Long = listCached().sumOf { it.length() }

    fun delete(filename: String): Boolean {
        val name = sanitize(filename) ?: return false
        var any = false
        for (d in dirsToSearch()) if (File(d, name).delete()) any = true
        return any
    }

    /** True if a fetch for [filename] is already running, so callers do not stack duplicates. */
    @Synchronized
    fun beginFetch(filename: String): Boolean {
        val name = sanitize(filename) ?: return false
        return inFlight.add(name)
    }

    @Synchronized
    fun endFetch(filename: String) {
        sanitize(filename)?.let { inFlight.remove(it) }
    }

    @Synchronized
    fun isFetching(filename: String): Boolean = sanitize(filename)?.let { inFlight.contains(it) } ?: false

    /**
     * Downloads [filename] from [masterHost]'s FPP file API. Blocking — call it off the sync
     * thread. Writes to a `.part` file and renames on success so a torn download can never be
     * mistaken for a playable sequence.
     */
    fun fetchFromMaster(masterHost: String, filename: String): Result {
        val name = sanitize(filename) ?: return Result.Missing(filename, "unsafe filename")
        if (masterHost.isBlank()) return Result.Missing(name, "no master address known yet")

        val target = File(dir, name)
        val part = File(dir, "$name.part")
        val url = URL("http://$masterHost/api/file/sequences/${encodePath(name)}")
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("Accept", "*/*")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
                return Result.Missing(name, "master requires HTTP auth (FPP password protection is on)")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                return Result.Missing(name, "master returned HTTP $code")
            }
            part.delete()
            conn.inputStream.use { input -> copyVerified(input, part) }
            if (!part.isFile || part.length() == 0L) {
                part.delete()
                return Result.Missing(name, "empty download")
            }
            target.delete()
            if (!part.renameTo(target)) {
                part.delete()
                return Result.Missing(name, "could not commit download")
            }
            evictIfNeeded()
            Log.i(TAG, "fetched $name (${target.length()} bytes) from $masterHost")
            return Result.Ready(target)
        } catch (t: Throwable) {
            part.delete()
            return Result.Missing(name, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Copies and refuses anything that is not actually an FSEQ. FPP's file API answers a missing
     * file with a 200 and an HTML error body in some versions, and a "sequence" that is really an
     * error page would otherwise sit in the cache failing to open forever.
     */
    private fun copyVerified(input: InputStream, dest: File) {
        dest.outputStream().use { out ->
            val buf = ByteArray(64 * 1024)
            var total = 0L
            var checked = false
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (!checked && total == 0L && n >= FseqHeader.PEEK_SIZE) {
                    require(FseqHeader.isFseq(buf.copyOf(FseqHeader.PEEK_SIZE))) {
                        "response is not an FSEQ file"
                    }
                    checked = true
                }
                out.write(buf, 0, n)
                total += n
            }
            require(checked) { "response too short to be an FSEQ file" }
        }
    }

    private fun encodePath(name: String): String =
        java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")

    /** Oldest-accessed-first eviction once the write volume exceeds its byte budget. */
    fun evictIfNeeded() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= budgetBytes) return
        for (f in files.sortedBy { it.lastModified() }) {
            if (total <= budgetBytes) break
            val size = f.length()
            if (f.delete()) {
                total -= size
                Log.i(TAG, "evicted ${f.name} ($size bytes)")
            }
        }
    }

    /**
     * Moves a cached sequence to another volume.
     *
     * Copy-then-delete rather than [File.renameTo], because a rename across mount points fails
     * and that is the only direction anyone actually wants to move a file in.
     */
    fun moveTo(filename: String, targetDir: File): Boolean {
        val name = sanitize(filename) ?: return false
        val src = localFile(name) ?: return false
        if (!targetDir.exists()) targetDir.mkdirs()
        val dest = File(targetDir, name)
        if (src.absolutePath == dest.absolutePath) return true
        val tmp = File(targetDir, "$name.moving")
        return try {
            src.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 20) } }
            if (tmp.length() != src.length()) {
                tmp.delete(); false
            } else {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete(); false
                } else {
                    src.delete(); true
                }
            }
        } catch (t: Throwable) {
            tmp.delete()
            false
        }
    }

    /** Marks [file] as recently used so eviction keeps the sequences actually being played. */
    fun touch(file: File) {
        try {
            file.setLastModified(System.currentTimeMillis())
        } catch (_: Throwable) {
        }
    }
}
