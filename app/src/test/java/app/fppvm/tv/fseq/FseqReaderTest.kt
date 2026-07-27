package app.fppvm.tv.fseq

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FseqReaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Frame n, channel c gets a value that is unique enough to catch an off-by-one anywhere. */
    private fun makeFrames(count: Int, channels: Int): List<ByteArray> =
        (0 until count).map { f ->
            ByteArray(channels) { c -> ((f * 31 + c * 7) and 0xFF).toByte() }
        }

    private fun assumeZstd() {
        Assume.assumeTrue(
            "zstd native library unavailable on this JVM: ${ZstdSupport.lastError}",
            ZstdSupport.isAvailable()
        )
    }

    @Test
    fun `uncompressed v2 reads every frame of a full-width window`() {
        val frames = makeFrames(37, 300)
        val f = FseqTestWriter.write(
            tmp.newFile("plain.fseq"), frames, compression = FseqHeader.Compression.NONE
        )
        FseqReader.open(f).use { reader ->
            assertEquals(2, reader.header.majorVersion)
            assertEquals(37, reader.header.numFrames)
            assertEquals(300, reader.header.frameSize)
            assertEquals(50, reader.header.stepTimeMs)
            val w = reader.openWindow(0, 300)
            val buf = ByteArray(300)
            for (i in frames.indices) {
                assertTrue("frame $i", w.readFrame(i, buf))
                assertArrayEquals("frame $i", frames[i], buf)
            }
        }
    }

    @Test
    fun `zstd blocks decode across block boundaries`() {
        assumeZstd()
        val frames = makeFrames(95, 512)
        val f = FseqTestWriter.write(
            tmp.newFile("z.fseq"), frames,
            compression = FseqHeader.Compression.ZSTD, framesPerBlock = 10
        )
        FseqReader.open(f).use { reader ->
            assertEquals(FseqHeader.Compression.ZSTD, reader.header.compression)
            assertEquals(10, reader.header.blocks.size)
            val w = reader.openWindow(0, 512)
            val buf = ByteArray(512)
            // Read out of order on purpose: a show seeks, and a reader that only works
            // front-to-back passes a sequential test and fails the first resync.
            for (i in listOf(0, 94, 47, 9, 10, 11, 60, 1, 93)) {
                assertTrue("frame $i", w.readFrame(i, buf))
                assertArrayEquals("frame $i", frames[i], buf)
            }
        }
    }

    @Test
    fun `zlib blocks decode too`() {
        val frames = makeFrames(41, 256)
        val f = FseqTestWriter.write(
            tmp.newFile("zl.fseq"), frames,
            compression = FseqHeader.Compression.ZLIB, framesPerBlock = 7
        )
        FseqReader.open(f).use { reader ->
            assertEquals(FseqHeader.Compression.ZLIB, reader.header.compression)
            val w = reader.openWindow(0, 256)
            val buf = ByteArray(256)
            for (i in listOf(40, 0, 20, 7, 6)) {
                assertTrue("frame $i", w.readFrame(i, buf))
                assertArrayEquals("frame $i", frames[i], buf)
            }
        }
    }

    @Test
    fun `a narrow window returns only its own channels`() {
        assumeZstd()
        val frames = makeFrames(30, 1000)
        val f = FseqTestWriter.write(tmp.newFile("w.fseq"), frames, framesPerBlock = 8)
        FseqReader.open(f).use { reader ->
            val start = 411
            val count = 96
            val w = reader.openWindow(start, count)
            val buf = ByteArray(count)
            for (i in 0 until 30) {
                assertTrue(w.readFrame(i, buf))
                assertArrayEquals(
                    "frame $i",
                    frames[i].copyOfRange(start, start + count),
                    buf
                )
            }
        }
    }

    @Test
    fun `a window past the end of the data reads back as black, not garbage`() {
        val frames = makeFrames(5, 120)
        val f = FseqTestWriter.write(
            tmp.newFile("short.fseq"), frames, compression = FseqHeader.Compression.NONE
        )
        FseqReader.open(f).use { reader ->
            val w = reader.openWindow(5000, 300)
            assertFalse(w.hasData)
            val buf = ByteArray(300)
            assertFalse(w.readFrame(0, buf))
            assertTrue(buf.all { it.toInt() == 0 })
        }
    }

    @Test
    fun `sparse ranges map stored bytes back to absolute channels`() {
        // Two disjoint ranges: 1000..1119 and 5000..5059. A stored frame is those laid end to end,
        // exactly as FPP's UncompressedFrameData::readFrame reassembles them.
        val ranges = listOf(
            FseqHeader.ChannelRange(1000, 120),
            FseqHeader.ChannelRange(5000, 60)
        )
        val frameSize = 180
        val frames = makeFrames(12, frameSize)
        val f = FseqTestWriter.write(
            tmp.newFile("sparse.fseq"), frames,
            compression = FseqHeader.Compression.NONE, sparseRanges = ranges
        )
        FseqReader.open(f).use { reader ->
            assertEquals(2, reader.header.ranges.size)
            assertEquals(5060, reader.header.maxChannel)

            // A window wholly inside the first range.
            val w1 = reader.openWindow(1030, 30)
            val b1 = ByteArray(30)
            assertTrue(w1.readFrame(3, b1))
            assertArrayEquals(frames[3].copyOfRange(30, 60), b1)

            // A window inside the second range: its bytes live after the first range in the frame.
            val w2 = reader.openWindow(5010, 20)
            val b2 = ByteArray(20)
            assertTrue(w2.readFrame(3, b2))
            assertArrayEquals(frames[3].copyOfRange(120 + 10, 120 + 30), b2)
        }
    }

    @Test
    fun `a window straddling a gap zero-fills the missing channels`() {
        val ranges = listOf(
            FseqHeader.ChannelRange(100, 30),
            FseqHeader.ChannelRange(200, 30)
        )
        val frames = makeFrames(4, 60)
        val f = FseqTestWriter.write(
            tmp.newFile("gap.fseq"), frames,
            compression = FseqHeader.Compression.NONE, sparseRanges = ranges
        )
        FseqReader.open(f).use { reader ->
            // 120..219 covers the tail of range 1, the gap, and the head of range 2.
            val w = reader.openWindow(120, 100)
            val buf = ByteArray(100)
            assertTrue(w.readFrame(1, buf))
            // 120..129 -> stored offsets 20..29
            assertArrayEquals(frames[1].copyOfRange(20, 30), buf.copyOfRange(0, 10))
            // 130..199 -> not in the file
            assertTrue(buf.copyOfRange(10, 80).all { it.toInt() == 0 })
            // 200..219 -> stored offsets 30..49
            assertArrayEquals(frames[1].copyOfRange(30, 50), buf.copyOfRange(80, 100))
        }
    }

    @Test
    fun `span mapping is computed correctly without touching a file`() {
        val ranges = listOf(
            FseqHeader.ChannelRange(0, 100),
            FseqHeader.ChannelRange(500, 100)
        )
        val spans = FseqReader.computeSpans(ranges, 50, 500)
        assertEquals(2, spans.size)
        assertEquals(FseqReader.Span(srcOffset = 50, length = 50, destOffset = 0), spans[0])
        assertEquals(FseqReader.Span(srcOffset = 100, length = 50, destOffset = 450), spans[1])
    }

    @Test
    fun `a non-fseq file is refused instead of being played as noise`() {
        val f = tmp.newFile("notes.txt")
        f.writeText("this is not a sequence, it is a text file with plenty of bytes in it")
        var threw = false
        try {
            FseqReader.open(f).close()
        } catch (t: Throwable) {
            threw = true
        }
        assertTrue("expected open() to reject a non-FSEQ file", threw)
    }

    @Test
    fun `step time and frame count drive the sequence duration`() {
        val frames = makeFrames(200, 90)
        val f = FseqTestWriter.write(
            tmp.newFile("t.fseq"), frames, stepTimeMs = 25,
            compression = FseqHeader.Compression.NONE
        )
        FseqReader.open(f).use {
            assertEquals(25, it.header.stepTimeMs)
            assertEquals(5000L, it.header.totalTimeMs)
        }
    }

    @Test
    fun `a window too large to cache a whole block still reads every frame`() {
        // 700 000 channels a frame with 8 frames a block is ~5.6 MB decoded per block; the reader's
        // budget forces a sliding window, and the sliding path is the one that only runs on the
        // biggest matrices, so it needs its own coverage rather than being exercised by accident.
        assumeZstd()
        val channels = 700_000
        val frames = (0 until 24).map { f ->
            ByteArray(channels) { c -> ((f * 13 + c) and 0xFF).toByte() }
        }
        val f = FseqTestWriter.write(
            tmp.newFile("huge.fseq"), frames,
            compression = FseqHeader.Compression.ZSTD, framesPerBlock = 8
        )
        FseqReader.open(f).use { reader ->
            val start = 1000
            val count = 600_000
            val w = reader.openWindow(start, count)
            val buf = ByteArray(count)
            // Sequential, which is how playback reads, then a couple of seeks.
            for (i in 0 until 24) {
                assertTrue("frame $i", w.readFrame(i, buf))
                assertArrayEquals("frame $i", frames[i].copyOfRange(start, start + count), buf)
            }
            for (i in listOf(23, 4, 17, 0)) {
                assertTrue("seek to $i", w.readFrame(i, buf))
                assertArrayEquals("seek to $i", frames[i].copyOfRange(start, start + count), buf)
            }
        }
    }

    @Test
    fun `panel-resolution geometry is representable`() {
        // 1280x720 is 2 764 800 channels; the reader must handle a frame that size.
        val channels = 1280 * 720 * 3
        val frames = (0 until 4).map { f -> ByteArray(channels) { c -> ((f + c) and 0xFF).toByte() } }
        val f = FseqTestWriter.write(
            tmp.newFile("hd.fseq"), frames,
            compression = FseqHeader.Compression.NONE
        )
        FseqReader.open(f).use { reader ->
            assertEquals(channels, reader.header.frameSize)
            val w = reader.openWindow(0, channels)
            val buf = ByteArray(channels)
            assertTrue(w.readFrame(2, buf))
            assertArrayEquals(frames[2], buf)
        }
    }
}
