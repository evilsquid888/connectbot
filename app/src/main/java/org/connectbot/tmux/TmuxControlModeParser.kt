package org.connectbot.tmux

import kotlinx.coroutines.channels.Channel

/**
 * Parses tmux control mode line-based protocol output into [TmuxEvent]s.
 *
 * tmux control mode sends output as newline-delimited text lines. Each line is either:
 * - A notification (starts with %)
 * - A command response block (%begin/%end/%error)
 * - Block body text (between %begin and %end/%error)
 *
 * Events are emitted to an UNLIMITED [Channel] to prevent backpressure from
 * blocking the I/O relay thread.
 *
 * Thread safety: [feedLine] should be called from a single thread (the relay's I/O thread).
 */
class TmuxControlModeParser {

    private val _events = Channel<TmuxEvent>(Channel.UNLIMITED)
    val events: Channel<TmuxEvent> = _events

    // Command response block state
    private var inBlock = false
    private var blockCommandNum = 0
    private var blockOutput = StringBuilder()

    /**
     * Process one line of control mode output (without trailing newline).
     */
    fun feedLine(line: String) {
        when {
            line.startsWith("%begin ") -> handleBegin(line)
            line.startsWith("%end ") -> handleEnd(false)
            line.startsWith("%error ") -> handleEnd(true)
            line.startsWith("%output ") -> handleOutput(line)
            line.startsWith("%extended-output ") -> handleExtendedOutput(line)
            line.startsWith("%window-add ") -> handleSingleArg(line, 12) { TmuxEvent.WindowAdd(it) }
            line.startsWith("%window-close ") -> handleSingleArg(line, 14) { TmuxEvent.WindowClose(it) }
            line.startsWith("%window-renamed ") -> handleTwoArgs(line, 16) { a, b -> TmuxEvent.WindowRenamed(a, b) }
            line.startsWith("%unlinked-window-add ") -> handleSingleArg(line, 21) { TmuxEvent.UnlinkedWindowAdd(it) }
            line.startsWith("%unlinked-window-close ") -> handleSingleArg(line, 23) { TmuxEvent.UnlinkedWindowClose(it) }
            line.startsWith("%unlinked-window-renamed ") -> handleTwoArgs(line, 25) { a, b -> TmuxEvent.UnlinkedWindowRenamed(a, b) }
            line.startsWith("%window-pane-changed ") -> handleTwoArgs(line, 21) { a, b -> TmuxEvent.WindowPaneChanged(a, b) }
            line.startsWith("%session-changed ") -> handleTwoArgs(line, 17) { a, b -> TmuxEvent.SessionChanged(a, b) }
            line.startsWith("%client-session-changed ") -> handleClientSessionChanged(line)
            line.startsWith("%session-renamed ") -> handleTwoArgs(line, 17) { a, b -> TmuxEvent.SessionRenamed(a, b) }
            line.startsWith("%sessions-changed") -> _events.trySend(TmuxEvent.SessionsChanged)
            line.startsWith("%session-window-changed ") -> handleTwoArgs(line, 24) { a, b -> TmuxEvent.SessionWindowChanged(a, b) }
            line.startsWith("%pane-mode-changed ") -> handleSingleArg(line, 19) { TmuxEvent.PaneModeChanged(it) }
            line.startsWith("%layout-change ") -> handleTwoArgs(line, 15) { a, b -> TmuxEvent.LayoutChanged(a, b) }
            line.startsWith("%pause ") -> handleSingleArg(line, 7) { TmuxEvent.Pause(it) }
            line.startsWith("%continue ") -> handleSingleArg(line, 10) { TmuxEvent.Continue(it) }
            line.startsWith("%subscription-changed ") -> handleTwoArgs(line, 22) { a, b -> TmuxEvent.SubscriptionChanged(a, b) }
            line.startsWith("%exit") -> _events.trySend(TmuxEvent.Exit)
            inBlock -> {
                if (blockOutput.isNotEmpty()) blockOutput.append('\n')
                blockOutput.append(line)
            }
            // Unknown line - ignore (defensive)
        }
    }

    /**
     * Parse %begin line: "%begin <timestamp> <cmd-num> <flags>"
     */
    private fun handleBegin(line: String) {
        val parts = line.split(' ', limit = 4)
        if (parts.size >= 3) {
            blockCommandNum = parts[2].toIntOrNull() ?: 0
            inBlock = true
            blockOutput.clear()
        }
    }

    /**
     * Emit CommandResponse and exit block mode.
     */
    private fun handleEnd(isError: Boolean) {
        if (inBlock) {
            _events.trySend(
                TmuxEvent.CommandResponse(
                    commandNum = blockCommandNum,
                    output = blockOutput.toString(),
                    isError = isError,
                ),
            )
            inBlock = false
            blockOutput.clear()
        }
    }

    /**
     * Parse %output: "%output %<pane-id> <octal-escaped-data>"
     * The pane ID starts with % and is followed by a space, then the escaped output.
     */
    private fun handleOutput(line: String) {
        // "%output %0 hello\012world"
        val afterPrefix = line.substring(8) // skip "%output "
        val spaceIdx = afterPrefix.indexOf(' ')
        if (spaceIdx < 0) return
        val paneId = afterPrefix.substring(0, spaceIdx)
        val escapedData = afterPrefix.substring(spaceIdx + 1)
        val decoded = decodeOctalEscapes(escapedData)
        _events.trySend(TmuxEvent.PaneOutput(paneId, decoded))
    }

    /**
     * Parse %extended-output: "%extended-output %<pane-id> <ms-behind> : <octal-escaped-data>"
     */
    private fun handleExtendedOutput(line: String) {
        val afterPrefix = line.substring(17) // skip "%extended-output "
        val spaceIdx = afterPrefix.indexOf(' ')
        if (spaceIdx < 0) return
        val paneId = afterPrefix.substring(0, spaceIdx)
        val rest = afterPrefix.substring(spaceIdx + 1)
        val colonIdx = rest.indexOf(" : ")
        if (colonIdx < 0) return
        val msBehind = rest.substring(0, colonIdx).toLongOrNull() ?: 0L
        val escapedData = rest.substring(colonIdx + 3)
        val decoded = decodeOctalEscapes(escapedData)
        _events.trySend(TmuxEvent.ExtendedOutput(paneId, msBehind, decoded))
    }

    /**
     * Parse %client-session-changed: "%client-session-changed <client> <session-id> <name>"
     */
    private fun handleClientSessionChanged(line: String) {
        val parts = line.substring(24).split(' ', limit = 3)
        if (parts.size >= 3) {
            _events.trySend(TmuxEvent.ClientSessionChanged(parts[0], parts[1], parts[2]))
        }
    }

    // Helper for single-argument notifications
    private inline fun handleSingleArg(line: String, prefixLen: Int, factory: (String) -> TmuxEvent) {
        val arg = line.substring(prefixLen).trim()
        if (arg.isNotEmpty()) {
            _events.trySend(factory(arg))
        }
    }

    // Helper for two-argument notifications (space-separated, second arg may contain spaces)
    private inline fun handleTwoArgs(
        line: String,
        prefixLen: Int,
        factory: (String, String) -> TmuxEvent,
    ) {
        val rest = line.substring(prefixLen)
        val spaceIdx = rest.indexOf(' ')
        if (spaceIdx > 0) {
            _events.trySend(factory(rest.substring(0, spaceIdx), rest.substring(spaceIdx + 1)))
        }
    }

    fun close() {
        _events.close()
    }

    companion object {
        /**
         * High-performance octal escape decoder for tmux control mode output.
         *
         * tmux encodes characters with ASCII value < 32 and backslash as \NNN
         * (3-digit octal). For example: \012 = LF, \033 = ESC, \134 = \.
         *
         * Uses pre-allocated output buffer and index-based iteration.
         * Avoids String intermediates and regex for hot-path performance.
         */
        fun decodeOctalEscapes(input: String): ByteArray {
            val output = ByteArray(input.length) // Decoded is always <= input length
            var outPos = 0
            var i = 0
            val len = input.length

            while (i < len) {
                val ch = input[i]
                if (ch == '\\' && i + 3 < len) {
                    val o1 = input[i + 1] - '0'
                    val o2 = input[i + 2] - '0'
                    val o3 = input[i + 3] - '0'
                    if (o1 in 0..3 && o2 in 0..7 && o3 in 0..7) {
                        output[outPos++] = ((o1 shl 6) or (o2 shl 3) or o3).toByte()
                        i += 4
                        continue
                    }
                }
                output[outPos++] = ch.code.toByte()
                i++
            }

            return if (outPos == output.size) output else output.copyOf(outPos)
        }
    }
}
