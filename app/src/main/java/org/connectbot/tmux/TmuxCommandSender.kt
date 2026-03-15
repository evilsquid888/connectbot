package org.connectbot.tmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/**
 * Response from a tmux command executed via control mode.
 */
data class TmuxCommandResponse(val output: String, val isError: Boolean)

/**
 * Sends tmux commands over the SSH transport and correlates responses.
 *
 * Uses a FIFO queue for command-response correlation: tmux control mode
 * responds to commands in the order they are sent. The server assigns
 * command numbers in %begin/%end blocks, but since responses arrive in
 * order, we maintain a simple queue of pending deferreds.
 *
 * All writes go through a [WriteFunction] that serializes access to the
 * SSH transport (typically via TerminalBridge.transportOperations channel).
 *
 * @param writeFn function to send bytes to the SSH transport (serialized)
 */
class TmuxCommandSender(
    private val writeFn: (ByteArray) -> Unit
) {
    private val pendingResponses = ArrayDeque<CompletableDeferred<TmuxCommandResponse>>()
    private val pendingLock = Any()

    /**
     * Called by the parser when a [TmuxEvent.CommandResponse] is received.
     * Completes the oldest pending deferred in the FIFO queue.
     */
    fun onCommandResponse(response: TmuxEvent.CommandResponse) {
        val deferred = synchronized(pendingLock) {
            pendingResponses.removeFirstOrNull()
        }
        deferred?.complete(TmuxCommandResponse(response.output, response.isError))
    }

    /**
     * Send a tmux command and await its response.
     * @param cmd the tmux command string (without trailing newline)
     * @param timeoutMs timeout in milliseconds (default 10 seconds)
     * @return the command response
     * @throws kotlinx.coroutines.TimeoutCancellationException if no response within timeout
     */
    suspend fun sendCommand(cmd: String, timeoutMs: Long = 10_000L): TmuxCommandResponse {
        val deferred = CompletableDeferred<TmuxCommandResponse>()
        synchronized(pendingLock) {
            pendingResponses.addLast(deferred)
        }
        writeFn("$cmd\n".toByteArray(Charsets.UTF_8))
        return withTimeout(timeoutMs) { deferred.await() }
    }

    /**
     * Send a tmux command without waiting for a response (fire-and-forget).
     */
    fun sendCommandFire(cmd: String) {
        writeFn("$cmd\n".toByteArray(Charsets.UTF_8))
    }

    // --- High-level commands ---

    suspend fun listWindows(format: String = "#{window_id} #{window_name}"): String = sendCommand("list-windows -F '$format'").output

    suspend fun listPanes(
        windowId: String,
        format: String = "#{pane_id} #{pane_width} #{pane_height} #{pane_active}"
    ): String = sendCommand("list-panes -t '$windowId' -F '$format'").output

    suspend fun newWindow(name: String? = null): TmuxCommandResponse {
        val nameArg = name?.let { " -n '${it.replace("'", "\\'")}'" } ?: ""
        return sendCommand("new-window$nameArg")
    }

    suspend fun killWindow(windowId: String): TmuxCommandResponse = sendCommand("kill-window -t '$windowId'")

    suspend fun selectWindow(windowId: String): TmuxCommandResponse = sendCommand("select-window -t '$windowId'")

    /**
     * Send printable text to a pane using literal mode (-l).
     * Single quotes in text are escaped for tmux shell.
     */
    suspend fun sendKeysLiteral(paneId: String, text: String): TmuxCommandResponse {
        val escaped = text.replace("'", "'\\''")
        return sendCommand("send-keys -t '$paneId' -l '$escaped'")
    }

    /**
     * Send bytes to a pane using hex mode (-H).
     * @param hexCodes space-separated hex byte values (e.g., "1b 5b 41" for ESC [ A)
     */
    suspend fun sendKeysHex(paneId: String, hexCodes: String): TmuxCommandResponse = sendCommand("send-keys -t '$paneId' -H $hexCodes")

    suspend fun resizeClient(width: Int, height: Int): TmuxCommandResponse = sendCommand("refresh-client -C ${width}x$height")

    suspend fun enableFlowControl(pauseAfterSeconds: Int = 1): TmuxCommandResponse = sendCommand("refresh-client -f pause-after=$pauseAfterSeconds")

    suspend fun resumePane(paneId: String): TmuxCommandResponse = sendCommand("refresh-client -A '$paneId:continue'")

    suspend fun getVersion(): String = sendCommand("display-message -p '#{version}'").output.trim()

    suspend fun capturePane(paneId: String): String = sendCommand("capture-pane -p -e -t '$paneId'").output

    suspend fun renameWindow(windowId: String, name: String): TmuxCommandResponse {
        val escaped = name.replace("'", "'\\''")
        return sendCommand("rename-window -t '$windowId' '$escaped'")
    }
}
