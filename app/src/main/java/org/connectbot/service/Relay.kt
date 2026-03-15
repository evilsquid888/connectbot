/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.service

import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.apache.harmony.niochar.charset.additional.IBM437
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.tmux.DcsSequenceDetector
import org.connectbot.tmux.TmuxControlModeParser
import org.connectbot.transport.AbsTransport
import timber.log.Timber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CharsetDecoder
import java.nio.charset.CharsetEncoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Coroutine-based relay that handles incoming data from the transport to the terminal buffer.
 * Handles charset decoding and East Asian character width calculations.
 *
 * @author Kenny Root
 */
class Relay(
    private val bridge: TerminalBridge,
    private val transport: AbsTransport,
    private val dispatchers: CoroutineDispatchers,
    encoding: String
) {

    private var currentCharset: Charset? = null
    private var decoder: CharsetDecoder? = null

    private val encoder: CharsetEncoder = StandardCharsets.UTF_8.newEncoder().apply {
        onMalformedInput(CodingErrorAction.REPLACE)
        onUnmappableCharacter(CodingErrorAction.REPLACE)
    }

    private val sourceBuffer = ByteBuffer.allocate(BUFFER_SIZE)
    private val charBuffer = CharBuffer.allocate(BUFFER_SIZE)
    private val destBuffer = ByteBuffer.allocate(BUFFER_SIZE)

    // tmux control mode state
    private val dcsDetector = DcsSequenceDetector()
    private var tmuxControlMode = false
    private val lineBuffer = StringBuilder()

    /**
     * Callback invoked when the DCS tmux control mode sequence is detected.
     * The bridge (or test) sets this to react to control mode activation.
     */
    var onTmuxControlModeDetected: (() -> Unit)? = null

    /**
     * Parser to receive line-buffered tmux control mode output.
     * Set externally once control mode is active.
     */
    var tmuxParser: TmuxControlModeParser? = null

    init {
        setCharset(encoding)
    }

    /**
     * Set the character set for decoding incoming data.
     *
     * @param encoding the character set name (e.g., "UTF-8", "CP437")
     */
    fun setCharset(encoding: String) {
        Timber.d("changing charset to $encoding")

        val charset = if (encoding == "CP437") {
            IBM437("IBM437", arrayOf("IBM437", "CP437"))
        } else {
            Charset.forName(encoding)
        }

        if (charset == currentCharset) {
            return
        }

        val newCd = charset.newDecoder().apply {
            onUnmappableCharacter(CodingErrorAction.REPLACE)
            onMalformedInput(CodingErrorAction.REPLACE)
        }

        currentCharset = charset
        decoder = newCd
    }

    /**
     * Get the current character set.
     *
     * @return the current Charset
     */
    fun getCharset(): Charset? = currentCharset

    /**
     * Start relaying data from transport to terminal buffer.
     * This is a suspend function that runs on IO dispatcher.
     */
    suspend fun start() = withContext(dispatchers.io) {
        decoder?.reset()
        encoder.reset()
        sourceBuffer.clear()
        charBuffer.clear()
        destBuffer.clear()
        dcsDetector.reset()

        var endOfInput = false

        try {
            while (isActive && !endOfInput) {
                val currentDecoder = decoder ?: continue

                val offset = sourceBuffer.position()
                val length = sourceBuffer.remaining()

                val bytesRead = if (length > 0) {
                    transport.read(sourceBuffer.array(), offset, length)
                } else {
                    0
                }

                if (bytesRead == -1) {
                    endOfInput = true
                } else {
                    // Advance position to reflect new data
                    sourceBuffer.position(offset + bytesRead)
                }

                sourceBuffer.flip()

                // If not yet in control mode, scan newly arrived bytes for the DCS sequence.
                if (!tmuxControlMode && bytesRead > 0) {
                    val scanResult = dcsDetector.scan(
                        sourceBuffer.array(),
                        offset,
                        bytesRead
                    )
                    if (scanResult.found) {
                        // Send bytes before the DCS sequence to the terminal emulator.
                        val preDcsEnd = if (scanResult.startedInPreviousBuffer) {
                            // The DCS sequence started in a previous buffer; all of the
                            // bytes currently in the (flipped) sourceBuffer that precede
                            // the sequence start have already been sent in prior iterations.
                            // Only bytes at position 0 up to (but not including) the
                            // sequence start that arrived in this read need to go to the
                            // terminal – but since startedInPreviousBuffer means the ESC
                            // was in a previous read we conservatively send nothing extra
                            // here to avoid double-sending.
                            sourceBuffer.position()
                        } else {
                            scanResult.startIndex
                        }

                        val currentPos = sourceBuffer.position()
                        if (preDcsEnd > currentPos) {
                            // Decode and send the pre-DCS bytes to the terminal.
                            val preDcsLimit = sourceBuffer.limit()
                            sourceBuffer.limit(preDcsEnd)
                            val decodeResult = currentDecoder.decode(sourceBuffer, charBuffer, false)
                            charBuffer.flip()
                            encoder.encode(charBuffer, destBuffer, false)
                            destBuffer.flip()
                            if (destBuffer.hasRemaining()) {
                                bridge.terminalEmulator.writeInput(
                                    destBuffer.array(),
                                    0,
                                    destBuffer.limit()
                                )
                            }
                            destBuffer.clear()
                            charBuffer.compact()
                            sourceBuffer.limit(preDcsLimit)
                        }

                        // Skip past the DCS sequence in the buffer.
                        sourceBuffer.position(scanResult.endIndex)

                        // Activate tmux control mode.
                        tmuxControlMode = true
                        onTmuxControlModeDetected?.invoke()

                        // Any remaining bytes after the DCS go to line buffering below.
                    }
                }

                while (sourceBuffer.hasRemaining() || endOfInput) {
                    val decodeResult = currentDecoder.decode(sourceBuffer, charBuffer, endOfInput)

                    charBuffer.flip()

                    if (tmuxControlMode) {
                        // In control mode: decoded chars → line buffer → parser
                        val text = charBuffer.toString()
                        feedToLineBuffer(text)
                        charBuffer.clear()
                    } else {
                        encoder.encode(charBuffer, destBuffer, endOfInput)
                        destBuffer.flip()

                        if (destBuffer.hasRemaining()) {
                            bridge.terminalEmulator.writeInput(destBuffer.array(), 0, destBuffer.limit())
                        }
                        destBuffer.clear()
                        charBuffer.compact()
                    }

                    if (decodeResult.isUnderflow) {
                        if (endOfInput) {
                            if (!tmuxControlMode) {
                                while (true) {
                                    val flushResult = encoder.flush(destBuffer)
                                    destBuffer.flip()
                                    if (destBuffer.hasRemaining()) {
                                        bridge.terminalEmulator.writeInput(
                                            destBuffer.array(),
                                            0,
                                            destBuffer.limit()
                                        )
                                    }
                                    destBuffer.clear()

                                    if (flushResult.isUnderflow) break
                                }
                            }
                            return@withContext
                        }

                        // Need more data to continue decoding
                        break
                    }

                    if (decodeResult.isOverflow) {
                        // Our buffer is full; let the inner loop run again to clear it
                        continue
                    }
                }

                // Move any remaining un-decoded bytes to the start of the buffer.
                sourceBuffer.compact()
            }
        } catch (e: IOException) {
            Timber.e(e, "Problem while handling incoming data in relay")
        }
    }

    /**
     * Feed decoded text into the line buffer in control mode.
     * Complete lines (terminated by \n) are sent to [tmuxParser].
     * Carriage returns (\r) are stripped.
     */
    internal fun feedToLineBuffer(text: String) {
        for (ch in text) {
            if (ch == '\n') {
                tmuxParser?.feedLine(lineBuffer.toString())
                lineBuffer.clear()
            } else if (ch != '\r') {
                lineBuffer.append(ch)
            }
        }
    }

    companion object {
        private const val TAG = "CB.Relay"
        private const val BUFFER_SIZE = 4096
    }
}
