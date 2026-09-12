package com.codepanda.otg.feature.mirror

/**
 * Splits an H.264 Annex-B byte stream into NAL units.
 *
 * `screenrecord` hands us an endless stream delimited by `00 00 01` /
 * `00 00 00 01` start codes, arriving in whatever sized chunks USB feels like.
 * The previous implementation rebuilt the entire pending buffer on *every*
 * chunk (`buffer = buffer + chunk`, then `copyOfRange` twice per NAL), which is
 * quadratic in the size of a keyframe and allocated megabytes per second — on a
 * phone that is simultaneously decoding video.
 *
 * This version keeps one growable array with a read cursor: bytes are appended
 * at the tail, consumed from the head, and the array is only compacted when the
 * head has advanced past half of it. Appending and emitting are both amortised
 * O(1) per byte, with no allocation beyond the emitted NAL itself.
 *
 * It is deliberately pure (no Android types) so the framing can be unit tested.
 */
class AnnexBSplitter(
    private val maxNalBytes: Int = DEFAULT_MAX_NAL,
    initialCapacity: Int = 128 * 1024,
) {
    private var buffer = ByteArray(initialCapacity)
    private var head = 0
    private var tail = 0

    /** Bytes currently buffered, i.e. a partial NAL awaiting its terminator. */
    val buffered: Int get() = tail - head

    /** Number of times an over-long NAL forced a resynchronisation. */
    var resyncCount: Int = 0
        private set

    fun append(data: ByteArray, length: Int = data.size) {
        ensureCapacity(length)
        System.arraycopy(data, 0, buffer, tail, length)
        tail += length
    }

    /**
     * Next complete NAL unit (start code stripped), or null if the buffer does
     * not yet hold one.
     */
    fun next(): ByteArray? {
        while (true) {
            val firstStart = indexOfStartCode(head) ?: return null
            val payloadStart = firstStart + startCodeLength(firstStart)
            val nextStart = indexOfStartCode(payloadStart)

            if (nextStart == null) {
                // Incomplete NAL. Guard against a stream that never delivers a
                // terminator: without this the buffer would grow without bound.
                if (tail - payloadStart > maxNalBytes) {
                    resyncCount++
                    head = tail
                    compactIfNeeded()
                }
                return null
            }

            head = nextStart
            compactIfNeeded()
            if (nextStart > payloadStart) {
                return buffer.copyOfRange(payloadStart, nextStart)
            }
            // Two adjacent start codes: skip the empty unit and keep scanning.
        }
    }

    /** At end of stream, whatever remains after the last start code. */
    fun drain(): ByteArray? {
        val firstStart = indexOfStartCode(head) ?: return null
        val payloadStart = firstStart + startCodeLength(firstStart)
        if (payloadStart >= tail) return null
        val nal = buffer.copyOfRange(payloadStart, tail)
        head = tail
        return nal
    }

    /**
     * Index of the next start code at or after [from], or null when the buffer
     * does not (yet) contain one.
     *
     * Both `00 00 01` and `00 00 00 01` are legal. A trailing `00 00 00` is
     * ambiguous — the next byte decides whether it is a four-byte code — so the
     * scan stops there and waits for more input instead of skipping over it.
     */
    private fun indexOfStartCode(from: Int): Int? {
        var i = from
        while (i + 2 < tail) {
            if (buffer[i] == ZERO && buffer[i + 1] == ZERO) {
                when (buffer[i + 2]) {
                    ONE -> return i
                    ZERO -> {
                        if (i + 3 >= tail) return null // undecidable until more bytes arrive
                        if (buffer[i + 3] == ONE) return i
                    }
                }
            }
            i++
        }
        return null
    }

    private fun startCodeLength(at: Int): Int = if (buffer[at + 2] == ONE) 3 else 4

    private fun ensureCapacity(extra: Int) {
        if (tail + extra <= buffer.size) return
        compact()
        if (tail + extra <= buffer.size) return
        var newSize = buffer.size
        while (newSize < tail + extra) newSize *= 2
        buffer = buffer.copyOf(newSize)
    }

    private fun compactIfNeeded() {
        if (head > buffer.size / 2) compact()
    }

    private fun compact() {
        if (head == 0) return
        System.arraycopy(buffer, head, buffer, 0, tail - head)
        tail -= head
        head = 0
    }

    companion object {
        private const val ZERO = 0.toByte()
        private const val ONE = 1.toByte()

        /** A 1080p keyframe is well under this; anything bigger is a broken stream. */
        const val DEFAULT_MAX_NAL = 4 * 1024 * 1024
    }
}
