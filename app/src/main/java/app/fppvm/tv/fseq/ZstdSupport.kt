package app.fppvm.tv.fseq

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Zstandard decompression for FSEQ channel blocks.
 *
 * Isolated behind one object because this is the only dependency with a native component:
 * `zstd-jni`'s Android AAR ships `libzstd-jni.so` per ABI (armeabi-v7a included, which is all the
 * test TV has). Keeping the call site to a single function means swapping in a pure-Java decoder
 * would be a one-file change if a device ever refused the native library.
 *
 * A stream — rather than a one-shot decompress into a sized buffer — is deliberate. FPP writes
 * each compression block with the streaming API terminated by `ZSTD_e_end`, which does not record
 * the decompressed size in the frame header, so the size cannot be read back from the data.
 * [FseqReader] knows the frame geometry and walks the stream instead.
 */
object ZstdSupport {

    @Volatile
    private var availability: Boolean? = null

    @Volatile
    var lastError: String? = null
        private set

    /**
     * True if the native zstd library is present and working, proven by a real round trip rather
     * than by the class merely resolving. Cheap after the first call.
     */
    fun isAvailable(): Boolean {
        availability?.let { return it }
        val ok = try {
            val probe = ByteArray(64) { (it * 7).toByte() }
            val compressed = com.github.luben.zstd.Zstd.compress(probe)
            val out = decompressingStream(compressed).use { it.readBytes() }
            out.contentEquals(probe)
        } catch (t: Throwable) {
            lastError = "${t.javaClass.simpleName}: ${t.message}"
            false
        }
        availability = ok
        return ok
    }

    /** Wraps [compressed] — one FSEQ compression block — in a decompressing stream. */
    @Throws(Exception::class)
    fun decompressingStream(compressed: ByteArray): InputStream =
        com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(compressed))
}
