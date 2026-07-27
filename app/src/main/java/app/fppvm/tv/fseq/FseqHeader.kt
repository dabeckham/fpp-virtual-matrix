package app.fppvm.tv.fseq

/**
 * Parsed FSEQ header. Ported from FPP `src/fseq/FSEQFile.cpp` (v1, v2 and the ESEQ variant).
 *
 * v2 fixed header (32 bytes), all multi-byte fields little-endian:
 * ```
 *   [0..3]   'PSEQ' (or 'FSEQ')
 *   [4..5]   u16  channel data offset
 *   [6]      u8   minor version
 *   [7]      u8   major version (2)
 *   [8..9]   u16  header size (fixed header + block table + sparse ranges)
 *   [10..13] u32  channel count per frame  (== the stored frame size, even when sparse)
 *   [14..17] u32  number of frames
 *   [18]     u8   step time, milliseconds
 *   [19]     u8   flags (reserved)
 *   [20]     u8   low nibble = compression type; high nibble = block count bits 11..8
 *   [21]     u8   block count bits 7..0
 *   [22]     u8   sparse range count
 *   [23]     u8   reserved
 *   [24..31] u64  unique id
 *   then     block table:   count * (u32 firstFrame, u32 compressedLength)
 *   then     sparse ranges: count * (u24 startChannel, u24 length)
 *   then     variable headers, up to the channel data offset
 * ```
 */
data class FseqHeader(
    val majorVersion: Int,
    val minorVersion: Int,
    val channelDataOffset: Int,
    /** Bytes stored per frame. For a sparse file this is the sum of the sparse range lengths. */
    val frameSize: Int,
    val numFrames: Int,
    val stepTimeMs: Int,
    val compression: Compression,
    /** File byte offset and frame span of every non-empty compression block. */
    val blocks: List<Block>,
    /** Absolute channel ranges present in each stored frame, in stored order. Never empty. */
    val ranges: List<ChannelRange>,
    val uniqueId: Long
) {
    enum class Compression { NONE, ZSTD, ZLIB }

    /** [firstFrame] is inclusive; [fileOffset] points at the block's first compressed byte. */
    data class Block(val firstFrame: Int, val fileOffset: Long, val compressedLength: Long)

    data class ChannelRange(val startChannel: Int, val length: Int)

    /** Highest absolute channel index + 1 that this file can supply. */
    val maxChannel: Int
        get() = ranges.maxOfOrNull { it.startChannel + it.length } ?: frameSize

    val totalTimeMs: Long get() = numFrames.toLong() * stepTimeMs

    companion object {
        const val V2_FIXED_HEADER_SIZE = 32
        const val V1_FIXED_HEADER_SIZE = 28
        const val BLOCK_ENTRY_SIZE = 8
        const val SPARSE_RANGE_SIZE = 6
        const val ESEQ_CHANNEL_DATA_OFFSET = 20
        const val ESEQ_STEP_TIME = 50

        /** Bytes of the file that must be read before [parse] can be called safely. */
        const val PEEK_SIZE = 8

        private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

        private fun u24(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16)

        private fun u32(b: ByteArray, o: Int): Long =
            (b[o].toLong() and 0xFF) or
                ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or
                ((b[o + 3].toLong() and 0xFF) shl 24)

        private fun u64(b: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
            return v
        }

        /** True if [peek] (at least [PEEK_SIZE] bytes) starts with a magic FSEQ/PSEQ/ESEQ identifier. */
        fun isFseq(peek: ByteArray): Boolean {
            if (peek.size < 4) return false
            val c0 = peek[0].toInt().toChar()
            return (c0 == 'P' || c0 == 'F' || c0 == 'E') &&
                peek[1].toInt().toChar() == 'S' &&
                peek[2].toInt().toChar() == 'E' &&
                peek[3].toInt().toChar() == 'Q'
        }

        /**
         * Parses [header] — which must hold at least the file's first `channelDataOffset` bytes.
         * [fileSize] is used the same way FPP uses it: to terminate the final block when the block
         * table is missing or inconsistent.
         *
         * @throws IllegalArgumentException if the file is not a usable FSEQ.
         */
        fun parse(header: ByteArray, fileSize: Long): FseqHeader {
            require(isFseq(header)) { "not an FSEQ file" }

            if (header[0].toInt().toChar() == 'E') return parseEseq(header, fileSize)

            val channelDataOffset = u16(header, 4)
            val minor = header[6].toInt() and 0xFF
            val major = header[7].toInt() and 0xFF
            require(header.size >= minOf(channelDataOffset, V2_FIXED_HEADER_SIZE)) {
                "FSEQ header truncated: have ${header.size}, need $channelDataOffset"
            }
            val frameSize = u32(header, 10).toInt()
            val numFrames = u32(header, 14).toInt()
            val stepTime = (header[18].toInt() and 0xFF).let { if (it == 0) 50 else it }
            require(frameSize > 0) { "FSEQ declares zero channels" }
            require(numFrames > 0) { "FSEQ declares zero frames" }

            if (major < 2) {
                // v1 is always uncompressed with a single implicit block and no sparse ranges.
                return FseqHeader(
                    majorVersion = major,
                    minorVersion = minor,
                    channelDataOffset = channelDataOffset,
                    frameSize = frameSize,
                    numFrames = numFrames,
                    stepTimeMs = stepTime,
                    compression = Compression.NONE,
                    blocks = listOf(Block(0, channelDataOffset.toLong(), fileSize - channelDataOffset)),
                    ranges = listOf(ChannelRange(0, frameSize)),
                    uniqueId = 0L
                )
            }

            val compression = when (header[20].toInt() and 0x0F) {
                0 -> Compression.NONE
                1 -> Compression.ZSTD
                2 -> Compression.ZLIB
                else -> throw IllegalArgumentException("unknown FSEQ compression type ${header[20].toInt() and 0x0F}")
            }
            // Block count is 12 bits split across two bytes, exactly as FPP packs it.
            val numBlocks = (((header[20].toInt() and 0xF0) shl 4) or (header[21].toInt() and 0xFF))
            val numSparse = header[22].toInt() and 0xFF
            val uniqueId = if (header.size >= 32) u64(header, 24) else 0L

            var readPos = V2_FIXED_HEADER_SIZE
            val blocks = ArrayList<Block>(maxOf(numBlocks, 1))
            var offset = channelDataOffset.toLong()
            var sawBlocks = false
            for (i in 0 until numBlocks) {
                if (readPos + BLOCK_ENTRY_SIZE > header.size) break
                val firstFrame = u32(header, readPos).toInt()
                val length = u32(header, readPos + 4)
                if (length > 0) {
                    blocks.add(Block(firstFrame, offset, length))
                    offset += length
                    sawBlocks = true
                }
                readPos += BLOCK_ENTRY_SIZE
            }

            if (compression == Compression.NONE || blocks.isEmpty()) {
                // Uncompressed data is one implicit block. An empty table on a compressed file
                // means a corrupt header; FPP's recovery is to treat the payload as one block and
                // we do the same rather than refusing to play the show.
                blocks.clear()
                blocks.add(Block(0, channelDataOffset.toLong(), maxOf(0L, fileSize - channelDataOffset)))
                sawBlocks = false
            } else {
                var end = offset
                if (!sawBlocks || end > fileSize || end <= channelDataOffset) end = fileSize
                // Fix up the final block's length so it reaches the true end of the channel data.
                val last = blocks.removeAt(blocks.size - 1)
                blocks.add(last.copy(compressedLength = maxOf(0L, end - last.fileOffset)))
            }

            val ranges = ArrayList<ChannelRange>(maxOf(numSparse, 1))
            for (i in 0 until numSparse) {
                if (readPos + SPARSE_RANGE_SIZE > header.size) break
                ranges.add(ChannelRange(u24(header, readPos), u24(header, readPos + 3)))
                readPos += SPARSE_RANGE_SIZE
            }
            if (ranges.isEmpty()) ranges.add(ChannelRange(0, frameSize))

            return FseqHeader(
                majorVersion = major,
                minorVersion = minor,
                channelDataOffset = channelDataOffset,
                frameSize = frameSize,
                numFrames = numFrames,
                stepTimeMs = stepTime,
                compression = compression,
                blocks = blocks,
                ranges = ranges,
                uniqueId = uniqueId
            )
        }

        private fun parseEseq(header: ByteArray, fileSize: Long): FseqHeader {
            // ESEQ: an uncompressed v2 payload with a 20-byte custom header carrying one model
            // range. Channel numbers in it are 1-based.
            val channelCount = u32(header, 8).toInt()
            require(channelCount > 0) { "ESEQ declares zero channels" }
            val modelStart = u32(header, 12).toInt()
            val modelLen = u32(header, 16).toInt()
            val frames = ((fileSize - ESEQ_CHANNEL_DATA_OFFSET) / channelCount).toInt()
            return FseqHeader(
                majorVersion = 2,
                minorVersion = 0,
                channelDataOffset = ESEQ_CHANNEL_DATA_OFFSET,
                frameSize = channelCount,
                numFrames = maxOf(1, frames),
                stepTimeMs = ESEQ_STEP_TIME,
                compression = Compression.NONE,
                blocks = listOf(
                    Block(0, ESEQ_CHANNEL_DATA_OFFSET.toLong(), fileSize - ESEQ_CHANNEL_DATA_OFFSET)
                ),
                ranges = listOf(
                    ChannelRange(if (modelStart > 0) modelStart - 1 else 0, if (modelLen > 0) modelLen else channelCount)
                ),
                uniqueId = 0L
            )
        }
    }
}
