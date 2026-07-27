package app.fppvm.tv.fseq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Multi-volume behaviour, which is the part that bites: a sequence written to a USB stick must
 * still be found after the write target moves, and pulling the stick must lose only what was on it.
 */
class SequenceStoreVolumesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seed(dir: File, name: String, bytes: Int = 32): File {
        dir.mkdirs()
        val f = File(dir, name)
        f.writeBytes(ByteArray(bytes) { 1 })
        return f
    }

    @Test
    fun `a name is resolved across every volume, not just the write target`() {
        val internal = tmp.newFolder("internal")
        val usb = tmp.newFolder("usb")
        seed(usb, "on-the-stick.fseq")
        seed(internal, "internal.fseq")

        val store = SequenceStore(internal, searchDirs = listOf(internal, usb))
        assertTrue(store.has("on-the-stick.fseq"))
        assertTrue(store.has("internal.fseq"))
        assertEquals(usb, store.localFile("on-the-stick.fseq")!!.parentFile)
    }

    @Test
    fun `switching the write target does not lose what is already cached`() {
        val internal = tmp.newFolder("internal")
        val usb = tmp.newFolder("usb")
        seed(internal, "old.fseq")
        val store = SequenceStore(internal, searchDirs = listOf(internal))

        // A stick is plugged in: writes move, reads widen.
        store.useDirectories(usb, listOf(internal, usb))
        assertEquals(usb, store.dir)
        assertTrue("previously cached sequence must still resolve", store.has("old.fseq"))
    }

    @Test
    fun `pulling the stick loses only what was on it`() {
        val internal = tmp.newFolder("internal")
        val usb = tmp.newFolder("usb")
        seed(internal, "kept.fseq")
        seed(usb, "gone.fseq")
        val store = SequenceStore(usb, searchDirs = listOf(internal, usb))
        assertTrue(store.has("gone.fseq"))

        store.useDirectories(internal, listOf(internal))
        assertTrue(store.has("kept.fseq"))
        assertNull(store.localFile("gone.fseq"))
    }

    @Test
    fun `listing spans volumes and hides partial transfers`() {
        val internal = tmp.newFolder("internal")
        val usb = tmp.newFolder("usb")
        seed(internal, "a.fseq")
        seed(usb, "b.fseq")
        seed(usb, "half.fseq.upload")
        seed(usb, "torn.fseq.part")
        val store = SequenceStore(internal, searchDirs = listOf(internal, usb))
        val names = store.listCached().map { it.name }
        assertEquals(listOf("a.fseq", "b.fseq"), names)
    }

    @Test
    fun `a name present on both volumes is listed once`() {
        val internal = tmp.newFolder("internal")
        val usb = tmp.newFolder("usb")
        seed(internal, "dup.fseq")
        seed(usb, "dup.fseq")
        val store = SequenceStore(internal, searchDirs = listOf(internal, usb))
        assertEquals(1, store.listCached().count { it.name == "dup.fseq" })
    }

    @Test
    fun `delete removes every copy so a stale one cannot resurface`() {
        val internal = tmp.newFolder("internal")
        val usb = tmp.newFolder("usb")
        seed(internal, "dup.fseq")
        seed(usb, "dup.fseq")
        val store = SequenceStore(internal, searchDirs = listOf(internal, usb))
        assertTrue(store.delete("dup.fseq"))
        assertNull(store.localFile("dup.fseq"))
    }
}
