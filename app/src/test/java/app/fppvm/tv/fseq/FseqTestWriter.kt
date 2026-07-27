package app.fppvm.tv.fseq

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/**
 * Writes FSEQ v2 files the way FPP writes them, so the reader is tested against real files rather
 * than against a mock of my own reading of the spec.
 *
 * Mirrors `V2FSEQFile::writeHeader` / the compression handlers in FPP `src/fseq/FSEQFile.cpp`:
 * fixed 32-byte header, a block table of `(firstFrame, compressedLength)` pairs, optional sparse
 * ranges, then each block written as one independent compressed stream.
 */
object FseqTestWriter {

    fun write(
        file: File,
        frames: List<ByteArray>,
        stepTimeMs: Int = 50,
        compression: FseqHeader.Compression = FseqHeader.Compression.ZSTD,
        framesPerBlock: Int = 10,
        sparseRanges: List<FseqHeader.ChannelRange> = emptyList()
    ): File {
        require(frames.isNotEmpty())
        val frameSize = frames[0].size
        require(frames.all { it.size == frameSize })

        val blockStarts = if (compression == FseqHeader.Compression.NONE) {
            emptyList()
        } else {
            frames.indices.step(framesPerBlock).toList()
        }
        val numBlocks = blockStarts.size
        val headerSize = 32 + numBlocks * 8 + sparseRanges.size * 6
        val chanDataOffset = roundTo4(headerSize)

        val header = ByteArray(chanDataOffset)
        header[0] = 'P'.code.toByte(); header[1] = 'S'.code.toByte()
        header[2] = 'E'.code.toByte(); header[3] = 'Q'.code.toByte()
        put16(header, 4, chanDataOffset)
        header[6] = 0 // minor
        header[7] = 2 // major
        put16(header, 8, headerSize)
        put32(header, 10, frameSize)
        put32(header, 14, frames.size)
        header[18] = stepTimeMs.toByte()
        header[19] = 0
        val compType = when (compression) {
            FseqHeader.Compression.NONE -> 0
            FseqHeader.Compression.ZSTD -> 1
            FseqHeader.Compression.ZLIB -> 2
        }
        header[20] = (((numBlocks shr 4) and 0xF0) or compType).toByte()
        header[21] = (numBlocks and 0xFF).toByte()
        header[22] = sparseRanges.size.toByte()
        header[23] = 0
        // unique id: 8 bytes, little-endian; content is irrelevant to reading
        for (i in 0..7) header[24 + i] = (i + 1).toByte()

        var pos = 32 + numBlocks * 8
        for (r in sparseRanges) {
            put24(header, pos, r.startChannel)
            put24(header, pos + 3, r.length)
            pos += 6
        }

        val payload = ByteArrayOutputStream()
        val blockLengths = ArrayList<Int>(numBlocks)
        if (numBlocks == 0) {
            for (f in frames) payload.write(f)
        } else {
            for ((bi, start) in blockStarts.withIndex()) {
                val end = if (bi + 1 < blockStarts.size) blockStarts[bi + 1] else frames.size
                val raw = ByteArrayOutputStream()
                for (i in start until end) raw.write(frames[i])
                val compressed = compress(raw.toByteArray(), compression)
                blockLengths.add(compressed.size)
                payload.write(compressed)
            }
            var bp = 32
            for ((bi, start) in blockStarts.withIndex()) {
                put32(header, bp, start)
                put32(header, bp + 4, blockLengths[bi])
                bp += 8
            }
        }

        file.outputStream().use {
            it.write(header)
            it.write(payload.toByteArray())
        }
        return file
    }

    private fun compress(data: ByteArray, type: FseqHeader.Compression): ByteArray = when (type) {
        FseqHeader.Compression.NONE -> data
        FseqHeader.Compression.ZSTD -> com.github.luben.zstd.Zstd.compress(data, 3)
        FseqHeader.Compression.ZLIB -> ByteArrayOutputStream().also { out ->
            DeflaterOutputStream(out, Deflater(6)).use { it.write(data) }
        }.toByteArray()
    }

    private fun roundTo4(v: Int) = (v + 3) and 3.inv()
    private fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    private fun put24(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte()
        b[o + 1] = ((v ushr 8) and 0xFF).toByte()
        b[o + 2] = ((v ushr 16) and 0xFF).toByte()
        b[o + 3] = ((v ushr 24) and 0xFF).toByte()
    }
}
