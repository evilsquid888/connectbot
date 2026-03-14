package org.connectbot.tmux

import org.assertj.core.api.Assertions.assertThat
import org.junit.Before
import org.junit.Test

class DcsSequenceDetectorTest {

    private lateinit var detector: DcsSequenceDetector

    // DCS sequence: ESC P 1 0 0 0 p
    private val dcsBytes = byteArrayOf(0x1B, 0x50, 0x31, 0x30, 0x30, 0x30, 0x70)

    @Before
    fun setUp() {
        detector = DcsSequenceDetector()
    }

    @Test
    fun `detect DCS in single buffer`() {
        val buffer = dcsBytes
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(0)
        assertThat(result.endIndex).isEqualTo(7)
        assertThat(result.startedInPreviousBuffer).isFalse()
    }

    @Test
    fun `detect DCS with leading data`() {
        val buffer = "hello".toByteArray() + dcsBytes
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(5) // after "hello"
        assertThat(result.endIndex).isEqualTo(12)
        assertThat(result.startedInPreviousBuffer).isFalse()
    }

    @Test
    fun `detect DCS with trailing data`() {
        val buffer = dcsBytes + "trailing".toByteArray()
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(0)
        assertThat(result.endIndex).isEqualTo(7)
    }

    @Test
    fun `detect DCS with leading and trailing data`() {
        val buffer = "pre".toByteArray() + dcsBytes + "post".toByteArray()
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(3)
        assertThat(result.endIndex).isEqualTo(10)
    }

    @Test
    fun `no DCS in buffer`() {
        val buffer = "just some normal data".toByteArray()
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isFalse()
    }

    @Test
    fun `detect DCS split across two buffers - split at byte 3`() {
        // First buffer: ESC P 1 (first 3 bytes)
        val buf1 = byteArrayOf(0x1B, 0x50, 0x31)
        val result1 = detector.scan(buf1, 0, buf1.size)
        assertThat(result1.found).isFalse()

        // Second buffer: 0 0 0 p (remaining 4 bytes)
        val buf2 = byteArrayOf(0x30, 0x30, 0x30, 0x70)
        val result2 = detector.scan(buf2, 0, buf2.size)
        assertThat(result2.found).isTrue()
        assertThat(result2.startedInPreviousBuffer).isTrue()
        assertThat(result2.endIndex).isEqualTo(4)
    }

    @Test
    fun `detect DCS split across two buffers - split at byte 1`() {
        // Just ESC
        val buf1 = byteArrayOf(0x1B)
        val result1 = detector.scan(buf1, 0, buf1.size)
        assertThat(result1.found).isFalse()

        // P 1 0 0 0 p
        val buf2 = byteArrayOf(0x50, 0x31, 0x30, 0x30, 0x30, 0x70)
        val result2 = detector.scan(buf2, 0, buf2.size)
        assertThat(result2.found).isTrue()
        assertThat(result2.startedInPreviousBuffer).isTrue()
    }

    @Test
    fun `detect DCS split at every position`() {
        // Test splitting the DCS at every possible boundary
        for (splitAt in 1 until dcsBytes.size) {
            val det = DcsSequenceDetector()
            val buf1 = dcsBytes.copyOfRange(0, splitAt)
            val buf2 = dcsBytes.copyOfRange(splitAt, dcsBytes.size)

            val result1 = det.scan(buf1, 0, buf1.size)
            assertThat(result1.found)
                .withFailMessage("Split at $splitAt: should not find in first buffer")
                .isFalse()

            val result2 = det.scan(buf2, 0, buf2.size)
            assertThat(result2.found)
                .withFailMessage("Split at $splitAt: should find in second buffer")
                .isTrue()
            assertThat(result2.startedInPreviousBuffer)
                .withFailMessage("Split at $splitAt: should report started in previous buffer")
                .isTrue()
        }
    }

    @Test
    fun `partial match reset on wrong byte`() {
        // ESC P 1 0 X (wrong byte) - should reset
        val buf1 = byteArrayOf(0x1B, 0x50, 0x31, 0x30, 0x99.toByte())
        val result1 = detector.scan(buf1, 0, buf1.size)
        assertThat(result1.found).isFalse()

        // Then a full DCS should still be detected
        val result2 = detector.scan(dcsBytes, 0, dcsBytes.size)
        assertThat(result2.found).isTrue()
        assertThat(result2.startedInPreviousBuffer).isFalse()
    }

    @Test
    fun `ESC followed by non-P resets match`() {
        val buf = byteArrayOf(0x1B, 0x41) // ESC A (not P)
        val result = detector.scan(buf, 0, buf.size)
        assertThat(result.found).isFalse()

        // Should still detect real DCS
        val result2 = detector.scan(dcsBytes, 0, dcsBytes.size)
        assertThat(result2.found).isTrue()
    }

    @Test
    fun `offset parameter is respected`() {
        val buffer = "padding".toByteArray() + dcsBytes
        // Scan starting at offset 7 (where DCS begins)
        val result = detector.scan(buffer, 7, dcsBytes.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(7)
        assertThat(result.endIndex).isEqualTo(14)
    }

    @Test
    fun `empty buffer returns not found`() {
        val result = detector.scan(byteArrayOf(), 0, 0)
        assertThat(result.found).isFalse()
    }

    @Test
    fun `reset clears partial match state`() {
        // Start a partial match
        val buf1 = byteArrayOf(0x1B, 0x50, 0x31)
        detector.scan(buf1, 0, buf1.size)

        // Reset
        detector.reset()

        // Now the remaining bytes should NOT complete a match
        val buf2 = byteArrayOf(0x30, 0x30, 0x30, 0x70)
        val result = detector.scan(buf2, 0, buf2.size)
        assertThat(result.found).isFalse()
    }

    @Test
    fun `multiple DCS sequences - only first detected`() {
        val buffer = dcsBytes + dcsBytes
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(0)
        assertThat(result.endIndex).isEqualTo(7)
        // Second DCS would be found in a subsequent scan
    }

    @Test
    fun `DCS with data before ESC that looks like ESC`() {
        // Buffer starts with a lone ESC (partial match) followed by actual DCS
        val buffer = byteArrayOf(0x1B, 0x41) + dcsBytes // ESC A then full DCS
        val result = detector.scan(buffer, 0, buffer.size)
        assertThat(result.found).isTrue()
        assertThat(result.startIndex).isEqualTo(2) // After ESC A
    }
}
