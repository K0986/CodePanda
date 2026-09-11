package com.codepanda.otg.feature.files

import org.junit.Assert.assertEquals
import org.junit.Test

class FileItemTest {

    @Test
    fun `formats a directory like ls -l`() {
        assertEquals("drwxr-xr-x", FileItem.formatMode(0x41ED)) // 040755
    }

    @Test
    fun `formats a regular file`() {
        assertEquals("-rw-r--r--", FileItem.formatMode(0x81A4)) // 0100644
    }

    @Test
    fun `formats a symlink`() {
        assertEquals("lrwxrwxrwx", FileItem.formatMode(0xA1FF)) // 0120777
    }

    @Test
    fun `unknown file type falls back to a question mark`() {
        assertEquals("?---------", FileItem.formatMode(0x0000))
    }
}
