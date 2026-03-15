package org.connectbot.ui.screens.console

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.Terminal
import org.connectbot.tmux.TmuxController
import org.connectbot.tmux.TmuxLayoutNode

/**
 * Recursive Compose layout that renders a tmux layout tree as split terminal panes.
 *
 * Leaf nodes render a [Terminal] composable. HSplit nodes render a [Column] (stacked),
 * VSplit nodes render a [Row] (side by side). Children are weighted by their dimensions.
 *
 * @param layoutNode the layout tree root
 * @param controller the tmux controller for looking up pane emulators
 * @param activePaneId the currently active pane ID (highlighted with a border)
 * @param onPaneTap callback when any pane is tapped
 */
@Composable
fun TmuxPaneLayout(
    layoutNode: TmuxLayoutNode,
    controller: TmuxController,
    activePaneId: String?,
    modifier: Modifier = Modifier,
    onPaneTap: (() -> Unit)? = null
) {
    when (layoutNode) {
        is TmuxLayoutNode.Leaf -> {
            val paneId = "%${layoutNode.paneId}"
            val emulator = controller.getPaneEmulator(paneId)
            val isActive = paneId == activePaneId
            val borderColor = if (isActive) {
                MaterialTheme.colorScheme.primary
            } else {
                Color.Transparent
            }

            Box(
                modifier = modifier
                    .border(
                        width = if (isActive) 2.dp else 1.dp,
                        color = borderColor
                    )
            ) {
                if (emulator != null) {
                    Terminal(
                        terminalEmulator = emulator,
                        modifier = Modifier.fillMaxSize(),
                        initialFontSize = 10.sp,
                        keyboardEnabled = true,
                        onTerminalTap = onPaneTap ?: {}
                    )
                }
            }
        }

        is TmuxLayoutNode.HSplit -> {
            Column(modifier = modifier) {
                layoutNode.children.forEachIndexed { index, child ->
                    val weight = child.height.toFloat() / layoutNode.height.coerceAtLeast(1)
                    if (index > 0) {
                        // Divider between horizontal panes
                        Box(
                            modifier = Modifier
                                .height(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                    TmuxPaneLayout(
                        layoutNode = child,
                        controller = controller,
                        activePaneId = activePaneId,
                        modifier = Modifier.weight(weight),
                        onPaneTap = onPaneTap
                    )
                }
            }
        }

        is TmuxLayoutNode.VSplit -> {
            Row(modifier = modifier) {
                layoutNode.children.forEachIndexed { index, child ->
                    val weight = child.width.toFloat() / layoutNode.width.coerceAtLeast(1)
                    if (index > 0) {
                        // Divider between vertical panes
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                        )
                    }
                    TmuxPaneLayout(
                        layoutNode = child,
                        controller = controller,
                        activePaneId = activePaneId,
                        modifier = Modifier.weight(weight),
                        onPaneTap = onPaneTap
                    )
                }
            }
        }
    }
}
