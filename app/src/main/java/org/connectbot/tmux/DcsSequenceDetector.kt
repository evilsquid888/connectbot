package org.connectbot.tmux

/**
 * Detects the tmux control mode DCS sequence \033P1000p in a byte stream.
 *
 * Handles cross-buffer detection: the 7-byte sequence may span multiple
 * [scan] calls. The detector maintains internal state between calls.
 *
 * The sequence bytes are: ESC(0x1B) P(0x50) '1'(0x31) '0'(0x30) '0'(0x30) '0'(0x30) 'p'(0x70)
 */
class DcsSequenceDetector {

    private val sequence = byteArrayOf(0x1B, 0x50, 0x31, 0x30, 0x30, 0x30, 0x70)

    /** How many bytes of the sequence have been matched so far (across buffers). */
    private var matchIndex = 0

    data class ScanResult(
        /** Whether the full DCS sequence was found. */
        val found: Boolean,
        /**
         * Index in the current buffer where the DCS sequence starts.
         * Only meaningful when [found] is true and [startedInPreviousBuffer] is false.
         */
        val startIndex: Int = -1,
        /** First byte index AFTER the DCS sequence in the current buffer. */
        val endIndex: Int = -1,
        /** True if the DCS sequence started in a previous buffer (partial match carried over). */
        val startedInPreviousBuffer: Boolean = false,
    )

    /**
     * Scan a buffer region for the DCS sequence.
     *
     * @param buffer the byte array to scan
     * @param offset start index in the buffer
     * @param length number of bytes to scan
     * @return scan result indicating whether the sequence was found
     */
    fun scan(buffer: ByteArray, offset: Int, length: Int): ScanResult {
        val matchIndexAtEntry = matchIndex

        for (i in offset until offset + length) {
            if (buffer[i] == sequence[matchIndex]) {
                matchIndex++
                if (matchIndex == sequence.size) {
                    val endIdx = i + 1
                    val startedInPrev = matchIndexAtEntry > 0
                    val startIdx = if (startedInPrev) {
                        offset // DCS started before this buffer
                    } else {
                        endIdx - sequence.size
                    }
                    matchIndex = 0
                    return ScanResult(
                        found = true,
                        startIndex = startIdx,
                        endIndex = endIdx,
                        startedInPreviousBuffer = startedInPrev,
                    )
                }
            } else {
                if (matchIndex > 0) {
                    matchIndex = 0
                }
                // Check if current byte starts a new potential match
                if (buffer[i] == sequence[0]) {
                    matchIndex = 1
                }
            }
        }
        return ScanResult(found = false)
    }

    fun reset() {
        matchIndex = 0
    }
}
