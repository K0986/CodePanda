package com.codepanda.otg.feature.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Framing tests for the live-mirror byte stream.
 *
 * `screenrecord` gives us NAL units split across arbitrary USB chunk
 * boundaries, so the splitter has to survive start codes that straddle two
 * reads — and do it without copying the whole pending buffer each time.
 */
class AnnexBSplitterTest {

    private val four = byteArrayOf(0, 0, 0, 1)
    private val three = byteArrayOf(0, 0, 1)

    private fun nal(type: Int, payload: Int, size: Int = 4): ByteArray =
        ByteArray(size) { index -> if (index == 0) type.toByte() else payload.toByte() }

    @Test
    fun `units are emitted between start codes, start code stripped`() {
        val splitter = AnnexBSplitter()
        val first = nal(7, 0xAA)
        val second = nal(1, 0xBB)
        splitter.append(four + first + four + second + four)

        assertArrayEquals(first, splitter.next())
        assertArrayEquals(second, splitter.next())
        assertNull(splitter.next())
    }

    @Test
    fun `three and four byte start codes are both understood`() {
        val splitter = AnnexBSplitter()
        val a = nal(5, 1)
        val b = nal(1, 2)
        splitter.append(three + a + four + b + three)

        assertArrayEquals(a, splitter.next())
        assertArrayEquals(b, splitter.next())
    }

    @Test
    fun `a unit split across chunk boundaries is reassembled`() {
        val splitter = AnnexBSplitter()
        val payload = ByteArray(300) { (it % 251).toByte() }.also { it[0] = 1 }

        // Feed the stream one byte at a time: the worst case for framing.
        val stream = four + payload + four
        stream.forEach { byte -> splitter.append(byteArrayOf(byte)) }

        assertArrayEquals(payload, splitter.next())
    }

    @Test
    fun `an incomplete unit is withheld until its terminator arrives`() {
        val splitter = AnnexBSplitter()
        val payload = nal(1, 0x11, size = 16)
        splitter.append(four + payload)

        assertNull("no terminator yet", splitter.next())
        assertTrue(splitter.buffered > 0)

        splitter.append(four)
        assertArrayEquals(payload, splitter.next())
    }

    @Test
    fun `the tail is available at end of stream`() {
        val splitter = AnnexBSplitter()
        val payload = nal(1, 0x22, size = 8)
        splitter.append(four + payload)

        assertNull(splitter.next())
        assertArrayEquals(payload, splitter.drain())
        assertNull(splitter.drain())
    }

    @Test
    fun `adjacent start codes do not produce empty units`() {
        val splitter = AnnexBSplitter()
        val payload = nal(1, 0x33)
        splitter.append(four + four + payload + four)

        assertArrayEquals(payload, splitter.next())
    }

    @Test
    fun `a stream that never terminates a unit is resynchronised instead of growing`() {
        val splitter = AnnexBSplitter(maxNalBytes = 1024, initialCapacity = 256)
        splitter.append(four)
        repeat(40) { splitter.append(ByteArray(128) { 0x77 }) }

        assertNull(splitter.next())
        assertEquals(1, splitter.resyncCount)
        // The buffer was dropped rather than accumulated without limit.
        assertTrue("buffered=${splitter.buffered}", splitter.buffered <= 128)
    }

    @Test
    fun `a large stream is handled without quadratic copying`() {
        val splitter = AnnexBSplitter()
        val unit = ByteArray(8 * 1024) { 1 }
        var emitted = 0

        // 8 MB through a 128 KB buffer: this would previously reallocate and
        // copy the whole pending buffer on every chunk.
        repeat(1024) {
            splitter.append(four + unit)
            while (splitter.next() != null) emitted++
        }
        assertTrue("emitted=$emitted", emitted >= 1020)
    }
}
