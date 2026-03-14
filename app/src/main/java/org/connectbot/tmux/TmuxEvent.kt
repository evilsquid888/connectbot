package org.connectbot.tmux

/**
 * Events emitted by [TmuxControlModeParser] when parsing tmux control mode output.
 *
 * Uses regular class (not data class) for [PaneOutput] and [ExtendedOutput] to avoid
 * incorrect equals/hashCode behavior with ByteArray fields.
 */
sealed class TmuxEvent {
    /** Output from a pane: %output %<pane-id> <octal-escaped-data> */
    class PaneOutput(val paneId: String, val data: ByteArray) : TmuxEvent()

    /** Extended output with latency info: %extended-output %<pane-id> <ms> : <data> (tmux 3.4+) */
    class ExtendedOutput(val paneId: String, val msBehind: Long, val data: ByteArray) : TmuxEvent()

    /** Response to a command wrapped in %begin/%end or %begin/%error blocks */
    data class CommandResponse(
        val commandNum: Int,
        val output: String,
        val isError: Boolean,
    ) : TmuxEvent()

    // Window notifications
    data class WindowAdd(val windowId: String) : TmuxEvent()
    data class WindowClose(val windowId: String) : TmuxEvent()
    data class WindowRenamed(val windowId: String, val name: String) : TmuxEvent()
    data class UnlinkedWindowAdd(val windowId: String) : TmuxEvent()
    data class UnlinkedWindowClose(val windowId: String) : TmuxEvent()
    data class UnlinkedWindowRenamed(val windowId: String, val name: String) : TmuxEvent()
    data class WindowPaneChanged(val windowId: String, val paneId: String) : TmuxEvent()

    // Session notifications
    data class SessionChanged(val sessionId: String, val name: String) : TmuxEvent()
    data class ClientSessionChanged(val client: String, val sessionId: String, val name: String) : TmuxEvent()
    data class SessionRenamed(val sessionId: String, val name: String) : TmuxEvent()
    data object SessionsChanged : TmuxEvent()
    data class SessionWindowChanged(val sessionId: String, val windowId: String) : TmuxEvent()

    // Layout: %layout-change @<wid> <layout> <visible-layout> <raw-flags>
    data class LayoutChanged(
        val windowId: String,
        val layout: String,
        val visibleLayout: String,
        val rawFlags: String,
    ) : TmuxEvent()

    data class PaneModeChanged(val paneId: String) : TmuxEvent()

    // Flow control
    data class Pause(val paneId: String) : TmuxEvent()
    data class Continue(val paneId: String) : TmuxEvent()

    // Subscriptions: %subscription-changed <name> <session-id> <window-id> <window-index> <pane-id> : <value>
    data class SubscriptionChanged(val name: String, val value: String) : TmuxEvent()

    // Client notifications
    data class ClientDetached(val client: String) : TmuxEvent()

    // Paste buffer notifications
    data class PasteBufferChanged(val bufferName: String) : TmuxEvent()
    data class PasteBufferDeleted(val bufferName: String) : TmuxEvent()

    // Error and message notifications
    data class ConfigError(val error: String) : TmuxEvent()
    data class Message(val message: String) : TmuxEvent()

    // Client exit (with optional reason like "server exited", "detached")
    data class Exit(val reason: String = "") : TmuxEvent()
}
