package org.connectbot.tmux

import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.connectbot.di.CoroutineDispatchers
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Central orchestrator for tmux control mode.
 *
 * Owns the parser and command sender, maintains window/pane state,
 * processes events from the parser channel, and exposes reactive state
 * for the UI layer.
 *
 * @param writeFn function to send bytes to the SSH transport
 * @param dispatchers coroutine dispatchers for threading
 * @param defaultFgColor default foreground color for new panes
 * @param defaultBgColor default background color for new panes
 * @param paneFactory factory for creating TmuxPane instances (injectable for testing)
 * @param onExitControlMode callback when tmux control mode exits (e.g., %exit received)
 */
class TmuxController(
    writeFn: (ByteArray) -> Unit,
    private val dispatchers: CoroutineDispatchers,
    private val defaultFgColor: Color = Color.White,
    private val defaultBgColor: Color = Color.Black,
    private val paneFactory: (paneId: String, cols: Int, rows: Int, onKeyInput: (ByteArray) -> Unit) -> TmuxPane =
        { paneId, cols, rows, onKeyInput ->
            TmuxPane(paneId, cols, rows, defaultFgColor, defaultBgColor, onKeyInput)
        },
    private val onExitControlMode: () -> Unit = {}
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val parser = TmuxControlModeParser()
    val sender = TmuxCommandSender(writeFn)

    // --- Reactive state for UI ---

    data class TmuxWindowState(
        val windowId: String,
        val name: String,
        val layout: TmuxLayoutNode? = null,
        val activePaneId: String? = null
    )

    private val _windows = MutableStateFlow<List<TmuxWindowState>>(emptyList())
    val windows: StateFlow<List<TmuxWindowState>> = _windows.asStateFlow()

    private val _activeWindowId = MutableStateFlow<String?>(null)
    val activeWindowId: StateFlow<String?> = _activeWindowId.asStateFlow()

    // Pane ID -> TmuxPane
    private val panes = ConcurrentHashMap<String, TmuxPane>()

    /**
     * Start the event processing loop.
     * Call this after the parser is set up and ready to receive events.
     */
    fun start() {
        scope.launch {
            processEvents()
        }
    }

    /**
     * Send initial commands to tmux to populate window/pane state.
     * Should be called after start() when the control mode is first activated.
     */
    suspend fun sendInitialCommands() {
        try {
            val version = sender.getVersion()
            Timber.d("tmux version: $version")

            val windowOutput = sender.listWindows(
                "#{window_id} #{window_name} #{window_layout} #{window_active}"
            )
            parseAndCreateWindows(windowOutput)
        } catch (e: Exception) {
            Timber.e(e, "Failed to send initial tmux commands")
        }
    }

    private suspend fun parseAndCreateWindows(windowOutput: String) {
        val windowStates = mutableListOf<TmuxWindowState>()
        var activeWinId: String? = null

        for (line in windowOutput.lines()) {
            if (line.isBlank()) continue
            val parts = line.split(' ', limit = 4)
            if (parts.size < 4) continue

            val windowId = parts[0] // @0
            val name = parts[1] // bash
            val layout = parts[2] // layout string
            val active = parts[3] // 1 or 0

            if (active == "1") activeWinId = windowId

            // Parse layout and create panes
            val layoutNode = try {
                TmuxLayoutParser.parse(layout)
            } catch (e: Exception) {
                Timber.w(e, "Failed to parse layout for window $windowId")
                null
            }

            // Get panes for this window
            val paneOutput = sender.listPanes(
                windowId,
                "#{pane_id} #{pane_width} #{pane_height} #{pane_active}"
            )
            var activePaneId: String? = null
            for (paneLine in paneOutput.lines()) {
                if (paneLine.isBlank()) continue
                val paneParts = paneLine.split(' ', limit = 4)
                if (paneParts.size < 4) continue

                val paneId = paneParts[0]
                val cols = paneParts[1].toIntOrNull() ?: 80
                val rows = paneParts[2].toIntOrNull() ?: 24
                val paneActive = paneParts[3]

                if (paneActive == "1") activePaneId = paneId

                if (!panes.containsKey(paneId)) {
                    panes[paneId] = paneFactory(paneId, cols, rows) { data ->
                        routeInput(paneId, data)
                    }
                }
            }

            windowStates.add(
                TmuxWindowState(
                    windowId = windowId,
                    name = name,
                    layout = layoutNode,
                    activePaneId = activePaneId
                )
            )
        }

        _windows.value = windowStates
        _activeWindowId.value = activeWinId
    }

    /**
     * Main event processing loop. Consumes events from the parser channel.
     */
    private suspend fun processEvents() {
        for (event in parser.events) {
            try {
                handleEvent(event)
            } catch (e: Exception) {
                Timber.e(e, "Error handling tmux event: $event")
            }
        }
    }

    internal fun handleEvent(event: TmuxEvent) {
        when (event) {
            is TmuxEvent.PaneOutput -> routeOutput(event.paneId, event.data)

            is TmuxEvent.ExtendedOutput -> routeOutput(event.paneId, event.data)

            is TmuxEvent.CommandResponse -> sender.onCommandResponse(event)

            is TmuxEvent.WindowAdd -> handleWindowAdd(event.windowId)

            is TmuxEvent.WindowClose -> handleWindowClose(event.windowId)

            is TmuxEvent.WindowRenamed -> handleWindowRenamed(event.windowId, event.name)

            is TmuxEvent.WindowPaneChanged -> handleWindowPaneChanged(event.windowId, event.paneId)

            is TmuxEvent.SessionWindowChanged -> _activeWindowId.value = event.windowId

            is TmuxEvent.LayoutChanged -> handleLayoutChanged(event.windowId, event.layout)

            is TmuxEvent.Exit -> handleExit(event.reason)

            is TmuxEvent.Pause -> { /* TODO: flow control */ }

            is TmuxEvent.Continue -> { /* TODO: flow control */ }

            // Events we don't need to handle in the controller
            else -> {}
        }
    }

    private fun routeOutput(paneId: String, data: ByteArray) {
        val pane = panes[paneId]
        if (pane != null) {
            pane.writeOutput(data)
        } else {
            Timber.w("Output for unknown pane: $paneId")
        }
    }

    /**
     * Route keyboard input from a pane to tmux via send-keys.
     * Uses hex encoding for all bytes to handle both printable and control characters.
     */
    fun routeInput(paneId: String, data: ByteArray) {
        if (data.isEmpty()) return
        val hex = data.joinToString(" ") { "%02x".format(it) }
        sender.sendCommandFire("send-keys -t '$paneId' -H $hex")
    }

    private fun handleWindowAdd(windowId: String) {
        _windows.update { current ->
            current + TmuxWindowState(
                windowId = windowId,
                name = windowId // Will be updated by WindowRenamed
            )
        }
    }

    private fun handleWindowClose(windowId: String) {
        _windows.update { current ->
            val closingWindow = current.find { it.windowId == windowId }
            closingWindow?.layout?.let { layout ->
                collectPaneIds(layout).forEach { paneId -> panes.remove(paneId) }
            }
            current.filter { it.windowId != windowId }
        }
    }

    private fun collectPaneIds(node: TmuxLayoutNode): List<String> = when (node) {
        is TmuxLayoutNode.Leaf -> listOf("%${node.paneId}")
        is TmuxLayoutNode.HSplit -> node.children.flatMap { collectPaneIds(it) }
        is TmuxLayoutNode.VSplit -> node.children.flatMap { collectPaneIds(it) }
    }

    private fun handleWindowRenamed(windowId: String, name: String) {
        _windows.update { current ->
            current.map { if (it.windowId == windowId) it.copy(name = name) else it }
        }
    }

    private fun handleWindowPaneChanged(windowId: String, paneId: String) {
        _windows.update { current ->
            current.map { if (it.windowId == windowId) it.copy(activePaneId = paneId) else it }
        }
    }

    private fun handleLayoutChanged(windowId: String, layoutString: String) {
        val layoutNode = try {
            TmuxLayoutParser.parse(layoutString)
        } catch (e: Exception) {
            Timber.w(e, "Failed to parse layout change for $windowId")
            return
        }

        // Create any new panes referenced in the layout
        createPanesFromLayout(layoutNode)

        _windows.update { current ->
            current.map { if (it.windowId == windowId) it.copy(layout = layoutNode) else it }
        }
    }

    private fun createPanesFromLayout(node: TmuxLayoutNode) {
        when (node) {
            is TmuxLayoutNode.Leaf -> {
                val paneId = "%${node.paneId}"
                if (!panes.containsKey(paneId)) {
                    panes[paneId] = paneFactory(paneId, node.width, node.height) { data ->
                        routeInput(paneId, data)
                    }
                }
            }

            is TmuxLayoutNode.HSplit -> node.children.forEach { createPanesFromLayout(it) }

            is TmuxLayoutNode.VSplit -> node.children.forEach { createPanesFromLayout(it) }
        }
    }

    private fun handleExit(reason: String) {
        Timber.i("tmux control mode exited: $reason")
        cleanup()
        onExitControlMode()
    }

    fun getPaneEmulator(paneId: String) = panes[paneId]?.terminalEmulator

    fun getPane(paneId: String) = panes[paneId]

    fun cleanup() {
        panes.clear()
        _windows.value = emptyList()
        _activeWindowId.value = null
        parser.close()
        scope.cancel()
    }
}
