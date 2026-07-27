package app.fppvm.tv.fseq

import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.zip.InflaterInputStream

/**
 * Random-access reader for FSEQ v1/v2/ESEQ that only ever materialises the channels a virtual
 * matrix actually needs.
 *
 * The obvious port of FPP's reader decompresses a whole block into a full-width frame buffer.
 * That is fine on a Pi with a dedicated 8 MB channel buffer; on a 925 MB Android TV a 500 000
 * channel sequence would cost 500 KB per frame and tens of megabytes per block. Instead
 * [openWindow] pins one contiguous absolute channel range up front and block decoding
 * stream-skips everything outside it, so steady-state memory is
 * `framesPerBlock * windowChannels` — a few hundred KB for a 96x48 matrix, no matter how wide
 * the sequence is.
 *
 * Not thread-safe: one reader belongs to one playback thread.
 */
class FseqReader private constructor(
    private val raf: RandomAccessFile,
    val header: FseqHeader,
    val file: File
) : Closeable {

    /** A span of one stored frame that overlaps the requested window. */
    internal data class Span(val srcOffset: Int, val length: Int, val destOffset: Int)

    companion object {
        /** Guard against a corrupt block table asking us to slurp the entire file into RAM. */
        private const val MAX_COMPRESSED_BLOCK_BYTES = 64L * 1024 * 1024

        /**
         * Ceiling on decoded frames held per window.
         *
         * A whole block is normally the right unit to cache: FPP aims for 64 KB blocks, and with a
         * floor of two frames per block a 1280x720 matrix (2.64 MiB a frame) still only means about
         * 5 MiB. But block sizing is the writer's choice, and this device has a 192 MB heap growth
         * limit — a file pairing a large window with many frames per block would otherwise OOM the
         * display mid-show. Past this budget the cache becomes a sliding window anchored at the
         * frame being asked for, which costs nothing on sequential playback.
         */
        private const val MAX_CACHE_BYTES = 24L * 1024 * 1024

        @Throws(Exception::class)
        fun open(file: File): FseqReader {
            val raf = RandomAccessFile(file, "r")
            try {
                val peek = ByteArray(FseqHeader.PEEK_SIZE)
                raf.readFully(peek)
                require(FseqHeader.isFseq(peek)) { "${file.name} is not an FSEQ file" }
                // Bytes 4..5 hold the channel-data offset; everything the header parser needs
                // lives below it. ESEQ has no such field, so use its fixed header size.
                val headerLen = if (peek[0].toInt().toChar() == 'E') {
                    FseqHeader.ESEQ_CHANNEL_DATA_OFFSET
                } else {
                    (peek[4].toInt() and 0xFF) or ((peek[5].toInt() and 0xFF) shl 8)
                }
                require(headerLen in 8..(1 shl 20)) { "implausible FSEQ header length $headerLen" }
                val buf = ByteArray(headerLen)
                raf.seek(0)
                raf.readFully(buf)
                val header = FseqHeader.parse(buf, file.length())
                return FseqReader(raf, header, file)
            } catch (t: Throwable) {
                try {
                    raf.close()
                } catch (_: Throwable) {
                }
                throw t
            }
        }

        /**
         * Maps a wanted absolute channel range onto offsets inside a stored frame.
         *
         * A stored frame is the file's channel ranges laid end to end in declaration order (FPP
         * `UncompressedFrameData::readFrame`), so a dense file is the single range
         * `[0, frameSize)` and this collapses to one span.
         */
        internal fun computeSpans(
            ranges: List<FseqHeader.ChannelRange>,
            wantStart: Int,
            wantCount: Int
        ): List<Span> {
            val out = ArrayList<Span>(2)
            val wantEnd = wantStart.toLong() + wantCount
            var frameOffset = 0L
            for (r in ranges) {
                val rEnd = r.startChannel.toLong() + r.length
                val overlapStart = maxOf(r.startChannel.toLong(), wantStart.toLong())
                val overlapEnd = minOf(rEnd, wantEnd)
                if (overlapEnd > overlapStart) {
                    out.add(
                        Span(
                            srcOffset = (frameOffset + (overlapStart - r.startChannel)).toInt(),
                            length = (overlapEnd - overlapStart).toInt(),
                            destOffset = (overlapStart - wantStart).toInt()
                        )
                    )
                }
                frameOffset += r.length
            }
            out.sortBy { it.srcOffset }
            return out
        }

        private fun readFully(input: InputStream, dest: ByteArray, offset: Int, length: Int): Boolean {
            var read = 0
            while (read < length) {
                val n = input.read(dest, offset + read, length - read)
                if (n < 0) return false
                read += n
            }
            return true
        }

        /** [InputStream.skip] is allowed to under-deliver; loop until the bytes are really gone. */
        private fun skipFully(input: InputStream, count: Long): Boolean {
            var remaining = count
            var scratch: ByteArray? = null
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                    continue
                }
                val s = scratch ?: ByteArray(8192).also { scratch = it }
                val n = input.read(s, 0, minOf(remaining, s.size.toLong()).toInt())
                if (n < 0) return false
                remaining -= n
            }
            return true
        }
    }

    /** Inclusive-exclusive frame span of block [i], clamped to the declared frame count. */
    private fun blockFrames(i: Int): IntRange {
        val start = header.blocks[i].firstFrame
        val end = if (i + 1 < header.blocks.size) {
            minOf(header.blocks[i + 1].firstFrame, header.numFrames)
        } else {
            header.numFrames
        }
        return start until maxOf(start, end)
    }

    private fun blockForFrame(frame: Int): Int {
        var lo = 0
        var hi = header.blocks.size - 1
        var result = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (header.blocks[mid].firstFrame <= frame) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }

    /**
     * Pins the absolute channel range `[startChannel, startChannel + channelCount)` and returns a
     * cursor over it. Channels the file does not carry read back as zero, which is what FPP's own
     * sparse handling produces.
     */
    fun openWindow(startChannel: Int, channelCount: Int): Window {
        require(channelCount > 0) { "channelCount must be positive" }
        return Window(startChannel, channelCount)
    }

    override fun close() {
        try {
            raf.close()
        } catch (_: Throwable) {
        }
    }

    inner class Window(val startChannel: Int, val channelCount: Int) {
        private val spans: List<Span> = computeSpans(header.ranges, startChannel, channelCount)

        /** How many decoded frames fit the cache budget. At least one, so a frame always fits. */
        private val maxCacheFrames: Int =
            (MAX_CACHE_BYTES / channelCount.coerceAtLeast(1)).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

        /** Window bytes for the frames of exactly one block. */
        private var cachedBlock = -1
        private var cachedFirstFrame = 0
        private var cachedFrameCount = 0
        private var cache: ByteArray = ByteArray(0)

        /** True when the file supplies at least one byte of the requested range. */
        val hasData: Boolean get() = spans.isNotEmpty()

        /** Number of decode failures seen; surfaced on the diagnostics screen. */
        var decodeErrors: Int = 0
            private set

        /**
         * Fills the first [channelCount] bytes of [dest] with the window's data for [frame].
         * Returns false (leaving [dest] zeroed) if the frame is out of range or its block could
         * not be decoded.
         */
        fun readFrame(frame: Int, dest: ByteArray): Boolean {
            require(dest.size >= channelCount) { "destination too small" }
            java.util.Arrays.fill(dest, 0, channelCount, 0)
            if (frame < 0 || frame >= header.numFrames || spans.isEmpty()) return false

            val blockIdx = blockForFrame(frame)
            val cached = blockIdx == cachedBlock &&
                frame >= cachedFirstFrame && frame < cachedFirstFrame + cachedFrameCount
            if (!cached && !loadBlock(blockIdx, frame)) return false
            val idx = frame - cachedFirstFrame
            if (idx < 0 || idx >= cachedFrameCount) return false
            System.arraycopy(cache, idx * channelCount, dest, 0, channelCount)
            return true
        }

        /**
         * Decodes part of block [blockIdx] into the cache, anchored so that [wantFrame] is the
         * first frame held. Normally that is the whole block; for a window big enough to blow the
         * cache budget it is [maxCacheFrames] frames starting at the one being asked for.
         */
        private fun loadBlock(blockIdx: Int, wantFrame: Int): Boolean {
            cachedBlock = -1
            val block = header.blocks.getOrNull(blockIdx) ?: return false
            val frames = blockFrames(blockIdx)
            val blockCount = frames.last - frames.first + 1
            if (blockCount <= 0) return false

            val anchor = wantFrame.coerceIn(frames.first, frames.last)
            val count = minOf(maxCacheFrames, frames.last - anchor + 1)
            if (count <= 0) return false
            val skip = anchor - frames.first

            val needed = count.toLong() * channelCount
            if (needed > Int.MAX_VALUE) return false
            if (cache.size < needed.toInt()) cache = ByteArray(needed.toInt())
            java.util.Arrays.fill(cache, 0, needed.toInt(), 0)

            val decoded = when (header.compression) {
                FseqHeader.Compression.NONE -> readUncompressed(block, anchor, count)
                else -> readCompressed(block, skip, count)
            }
            if (decoded <= 0) {
                decodeErrors++
                return false
            }
            if (decoded < count) decodeErrors++
            cachedBlock = blockIdx
            cachedFirstFrame = anchor
            // Only advertise the frames that actually decoded; a truncated tail block then reads
            // as "no data" rather than silently serving another frame's pixels.
            cachedFrameCount = decoded
            return true
        }

        /**
         * Uncompressed data needs no block staging: seek straight to each frame's spans. Reading
         * the whole block would mean holding the entire (potentially multi-GB) file section.
         */
        private fun readUncompressed(block: FseqHeader.Block, firstFrame: Int, count: Int): Int {
            var done = 0
            try {
                for (f in 0 until count) {
                    val frameBase = block.fileOffset + (firstFrame + f).toLong() * header.frameSize
                    val destBase = f * channelCount
                    for (s in spans) {
                        raf.seek(frameBase + s.srcOffset)
                        raf.readFully(cache, destBase + s.destOffset, s.length)
                    }
                    done = f + 1
                }
            } catch (t: Throwable) {
                // Fall through with however many frames we got.
            }
            return done
        }

        /** Decodes [count] frames from [block], after discarding [skipFrames] leading frames. */
        private fun readCompressed(block: FseqHeader.Block, skipFrames: Int, count: Int): Int {
            if (block.compressedLength <= 0 || block.compressedLength > MAX_COMPRESSED_BLOCK_BYTES) return 0
            val compressed = ByteArray(block.compressedLength.toInt())
            try {
                raf.seek(block.fileOffset)
                raf.readFully(compressed)
            } catch (t: Throwable) {
                return 0
            }
            val stream: InputStream = try {
                when (header.compression) {
                    FseqHeader.Compression.ZSTD -> ZstdSupport.decompressingStream(compressed)
                    FseqHeader.Compression.ZLIB -> InflaterInputStream(compressed.inputStream())
                    else -> return 0
                }
            } catch (t: Throwable) {
                return 0
            }
            var done = 0
            stream.use { input ->
                try {
                    // Zstd/zlib are sequential, so reaching the anchor means decoding and throwing
                    // away everything before it. Only happens when the cache budget forces a
                    // sliding window; a normal block starts at its first frame.
                    if (skipFrames > 0 &&
                        !skipFully(input, skipFrames.toLong() * header.frameSize)
                    ) {
                        return@use
                    }
                    outer@ for (f in 0 until count) {
                        var pos = 0
                        val destBase = f * channelCount
                        for (s in spans) {
                            if (s.srcOffset > pos) {
                                if (!skipFully(input, (s.srcOffset - pos).toLong())) break@outer
                                pos = s.srcOffset
                            }
                            if (!readFully(input, cache, destBase + s.destOffset, s.length)) break@outer
                            pos += s.length
                        }
                        done = f + 1
                        // Advance past the rest of the frame. Running out here is expected on the
                        // final frame of the final block, so it is not an error by itself.
                        if (pos < header.frameSize && !skipFully(input, (header.frameSize - pos).toLong())) {
                            break@outer
                        }
                    }
                } catch (t: Throwable) {
                    // Keep whatever decoded cleanly.
                }
            }
            return done
        }
    }
}
