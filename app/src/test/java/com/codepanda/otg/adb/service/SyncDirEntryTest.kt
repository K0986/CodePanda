package com.codepanda.otg.adb.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** st_mode decoding used by the sync protocol's DENT/STAT replies. */
class SyncDirEntryTest {

    private fun entry(mode: Int) = SyncDirEntry(name = "x", mode = mode, size = 0, mtimeSeconds = 0)

    @Test
    fun `directory mode is recognised`() {
        val dir = entry(0x41ED) // 040755
        assertTrue(dir.isDirectory)
        assertFalse(dir.isRegularFile)
        assertFalse(dir.isSymlink)
    }

    @Test
    fun `regular file mode is recognised`() {
        val file = entry(0x81A4) // 0100644
        assertTrue(file.isRegularFile)
        assertFalse(file.isDirectory)
    }

    @Test
    fun `symlink mode is recognised`() {
        val link = entry(0xA1FF) // 0120777
        assertTrue(link.isSymlink)
        assertFalse(link.isDirectory)
    }

    @Test
    fun `stat with mode zero means the path does not exist`() {
        assertFalse(SyncStat(mode = 0, size = 0, mtimeSeconds = 0).exists)
        assertTrue(SyncStat(mode = 0x81A4, size = 1, mtimeSeconds = 0).exists)
    }
}
