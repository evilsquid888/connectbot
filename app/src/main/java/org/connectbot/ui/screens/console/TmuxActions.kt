package org.connectbot.ui.screens.console

import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import org.connectbot.tmux.TmuxController

/**
 * Tmux-specific menu items shown when the active tab is a tmux window.
 *
 * @param controller the tmux controller
 * @param windowId the current tmux window ID
 * @param onDismiss callback to dismiss the dropdown menu
 */
@Composable
fun TmuxMenuItems(
    controller: TmuxController,
    windowId: String,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    DropdownMenuItem(
        text = { Text("New Window") },
        onClick = {
            onDismiss()
            scope.launch { controller.sender.newWindow() }
        }
    )

    DropdownMenuItem(
        text = { Text("Split Horizontally") },
        onClick = {
            onDismiss()
            controller.sender.sendCommandFire("split-window -v")
        }
    )

    DropdownMenuItem(
        text = { Text("Split Vertically") },
        onClick = {
            onDismiss()
            controller.sender.sendCommandFire("split-window -h")
        }
    )

    DropdownMenuItem(
        text = { Text("Close Pane") },
        onClick = {
            onDismiss()
            controller.sender.sendCommandFire("kill-pane")
        }
    )

    DropdownMenuItem(
        text = { Text("Close Window") },
        onClick = {
            onDismiss()
            scope.launch { controller.sender.killWindow(windowId) }
        }
    )

    DropdownMenuItem(
        text = { Text("Detach") },
        onClick = {
            onDismiss()
            controller.sender.sendCommandFire("detach-client")
        }
    )
}
