package org.connectbot.ui.screens.console

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.connectbot.tmux.TmuxController

/**
 * Describes a tmux menu action.
 */
data class TmuxMenuAction(
    val label: String,
    val onClick: () -> Unit
)

/**
 * Build the list of tmux-specific menu actions for the current tmux window.
 *
 * @param controller the tmux controller
 * @param windowId the current tmux window ID
 * @param scope coroutine scope for suspend actions
 * @param onDismiss callback to dismiss the dropdown menu
 */
fun buildTmuxMenuActions(
    controller: TmuxController,
    windowId: String,
    scope: CoroutineScope,
    onDismiss: () -> Unit
): List<TmuxMenuAction> = listOf(
    TmuxMenuAction("New Window") {
        onDismiss()
        scope.launch { controller.sender.newWindow() }
    },
    TmuxMenuAction("Split Horizontally") {
        onDismiss()
        controller.sender.sendCommandFire("split-window -v")
    },
    TmuxMenuAction("Split Vertically") {
        onDismiss()
        controller.sender.sendCommandFire("split-window -h")
    },
    TmuxMenuAction("Close Pane") {
        onDismiss()
        controller.sender.sendCommandFire("kill-pane")
    },
    TmuxMenuAction("Close Window") {
        onDismiss()
        scope.launch { controller.sender.killWindow(windowId) }
    },
    TmuxMenuAction("Detach") {
        onDismiss()
        controller.sender.sendCommandFire("detach-client")
    }
)
