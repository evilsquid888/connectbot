package org.connectbot.tmux

import androidx.compose.ui.graphics.Color
import org.connectbot.terminal.TerminalEmulator
import org.connectbot.terminal.TerminalEmulatorFactory

/**
 * Operations interface used by [TmuxPane] to interact with a terminal emulator.
 *
 * This non-sealed interface exists so that [TmuxPane] can be tested without the native
 * [TerminalEmulator] implementation (which is a sealed interface restricted to the
 * termlib library and requires native JNI code at runtime).
 */
interface PaneTerminalOps {
    fun writeInput(data: ByteArray)
    fun resize(cols: Int, rows: Int)
}

/**
 * Default [PaneTerminalOps] adapter that wraps a real [TerminalEmulator].
 * Accessible via [TmuxPane.terminalEmulator].
 */
private class TerminalEmulatorOps(val emulator: TerminalEmulator) : PaneTerminalOps {
    override fun writeInput(data: ByteArray) = emulator.writeInput(data)
    override fun resize(cols: Int, rows: Int) = emulator.resize(cols, rows)
}

/**
 * Lightweight virtual terminal representing a single tmux pane.
 *
 * Each pane owns its own [TerminalEmulator] instance for processing VT100/escape
 * sequences from tmux %output data. Much lighter than [TerminalBridge] — no transport,
 * no network state, no profile observation.
 *
 * In production the [terminalEmulator] is created via [TerminalEmulatorFactory]. For
 * testing, a [terminalOpsFactory] can be supplied to avoid native-library dependencies
 * while still verifying write/resize delegation.
 *
 * @param paneId the tmux pane identifier (e.g., "%0")
 * @param initialCols initial column count
 * @param initialRows initial row count
 * @param defaultFgColor default foreground color
 * @param defaultBgColor default background color
 * @param onKeyboardInput callback for keyboard input from this pane's terminal
 * @param terminalOpsFactory factory for terminal operations; injectable for testing
 */
class TmuxPane(
    val paneId: String,
    initialCols: Int,
    initialRows: Int,
    defaultFgColor: Color,
    defaultBgColor: Color,
    private val onKeyboardInput: (ByteArray) -> Unit,
    terminalOpsFactory: (onKeyInput: (ByteArray) -> Unit) -> PaneTerminalOps = { onKeyInput ->
        TerminalEmulatorOps(
            TerminalEmulatorFactory.create(
                initialRows = initialRows,
                initialCols = initialCols,
                defaultForeground = defaultFgColor,
                defaultBackground = defaultBgColor,
                onKeyboardInput = onKeyInput,
                onBell = { },
                onResize = { }
            )
        )
    }
) {
    private val terminalOps: PaneTerminalOps = terminalOpsFactory { data -> onKeyboardInput(data) }

    /**
     * The underlying [TerminalEmulator], non-null when the production (default) factory
     * is used. Null when a custom [terminalOpsFactory] is injected (e.g., in tests).
     *
     * Callers that render terminal state should use the default constructor so this
     * property is non-null.
     */
    val terminalEmulator: TerminalEmulator? = (terminalOps as? TerminalEmulatorOps)?.emulator

    /**
     * Write output data received from tmux %output event to this pane's terminal.
     */
    fun writeOutput(data: ByteArray) {
        terminalOps.writeInput(data)
    }

    /**
     * Resize this pane's terminal emulator.
     */
    fun resize(cols: Int, rows: Int) {
        terminalOps.resize(cols, rows)
    }
}
