package app.fppvm.tv.fseq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SequenceStoreTest {

    @Test
    fun `ordinary show filenames survive intact`() {
        assertEquals("Wizards In Winter.fseq", SequenceStore.sanitize("Wizards In Winter.fseq"))
        assertEquals("show-2026_v2.fseq", SequenceStore.sanitize("show-2026_v2.fseq"))
    }

    @Test
    fun `the master's path is reduced to a leaf`() {
        // Sync packets carry whatever path the player used; only the name means anything here.
        assertEquals("a.fseq", SequenceStore.sanitize("/home/fpp/media/sequences/a.fseq"))
        assertEquals("a.fseq", SequenceStore.sanitize("C:\\shows\\a.fseq"))
    }

    @Test
    fun `traversal and control characters are refused`() {
        assertNull(SequenceStore.sanitize(".."))
        assertNull(SequenceStore.sanitize("../../etc/passwd/"))
        assertNull(SequenceStore.sanitize(""))
        assertNull(SequenceStore.sanitize("   "))
        assertNull(SequenceStore.sanitize("bad\u0000name.fseq"))
        assertNull(SequenceStore.sanitize("a\nb.fseq"))
    }
}
