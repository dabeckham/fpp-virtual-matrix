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

    /**
     * Decompresses a whole block in one call, returning the number of bytes produced.
     *
     * Measured on the test TV, the streaming path above manages about 11 MiB/s — nowhere near what
     * libzstd does natively — because every read crosses JNI and lands in an intermediate buffer.
     * One-shot decompression hands libzstd the whole block and gets it back in a single crossing.
     *
     * This needs the decompressed size up front. FPP writes blocks with the streaming API and
     * `ZSTD_e_end`, which does not record the content size in the frame header, so it cannot be
     * read from the data — but the caller knows it from the frame geometry, which is what makes
     * this usable at all.
     */
    @Throws(Exception::class)
    fun decompressWhole(compressed: ByteArray, dest: ByteArray, destSize: Int): Int {
        val n = com.github.luben.zstd.Zstd.decompressByteArray(
            dest, 0, destSize, compressed, 0, compressed.size
        )
        if (com.github.luben.zstd.Zstd.isError(n)) {
            throw IllegalStateException("zstd: " + com.github.luben.zstd.Zstd.getErrorName(n))
        }
        return n.toInt()
    }
}
