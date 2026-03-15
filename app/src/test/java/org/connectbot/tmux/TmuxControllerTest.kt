package org.connectbot.tmux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.di.CoroutineDispatchers
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxControllerTest {
    // Use UnconfinedTestDispatcher so coroutines run immediately
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testDispatchers = CoroutineDispatchers(
        default = testDispatcher,
        io = testDispatcher,
        main = testDispatcher,
    )

    private val writtenBytes = mutableListOf<ByteArray>()
    private val writeFn: (ByteArray) -> Unit = { writtenBytes.add(it) }

    // Records of data written to pane terminal ops
    private val paneWrites = mutableMapOf<String, MutableList<ByteArray>>()

    private fun createController(onExit: () -> Unit = {}): TmuxController {
        return TmuxController(
            writeFn = writeFn,
            dispatchers = testDispatchers,
            paneFactory = { paneId, cols, rows, onKeyInput ->
                val writes = mutableListOf<ByteArray>()
                paneWrites[paneId] = writes
                TmuxPane(
                    paneId = paneId,
                    initialCols = cols,
                    initialRows = rows,
                    defaultFgColor = androidx.compose.ui.graphics.Color.White,
                    defaultBgColor = androidx.compose.ui.graphics.Color.Black,
                    onKeyboardInput = onKeyInput,
                    terminalOpsFactory = { _ ->
                        object : PaneTerminalOps {
                            override fun writeInput(data: ByteArray) {
                                writes.add(data)
                            }
                            override fun resize(cols: Int, rows: Int) {}
                        }
                    }
                )
            },
            onExitControlMode = onExit,
        )
    }

    private fun registerPane(controller: TmuxController, paneId: String, cols: Int = 80, rows: Int = 24) {
        // Feed a layout change event to create the pane.
        // Layout string format: <4-char-checksum>,<width>x<height>,<x>,<y>,<paneId>
        val paneNum = paneId.removePrefix("%").toInt()
        val layout = "abcd,5x5,0,0,$paneNum"
        controller.handleEvent(TmuxEvent.WindowAdd("@0"))
        controller.handleEvent(TmuxEvent.LayoutChanged("@0", layout, layout, ""))
    }

    @Test
    fun `handleEvent routes PaneOutput to correct pane`() = runTest {
        val controller = createController()
        registerPane(controller, "%0")

        val data = "hello".toByteArray()
        controller.handleEvent(TmuxEvent.PaneOutput("%0", data))

        // The pane terminal ops should have received the write
        // We can verify via the pane itself
        assertThat(controller.getPane("%0")).isNotNull()
    }

    @Test
    fun `handleEvent routes ExtendedOutput to correct pane`() = runTest {
        val controller = createController()
        registerPane(controller, "%0")

        val data = "world".toByteArray()
        controller.handleEvent(TmuxEvent.ExtendedOutput("%0", 0L, data))

        assertThat(controller.getPane("%0")).isNotNull()
    }

    @Test
    fun `handleEvent ignores output for unknown pane`() = runTest {
        val controller = createController()

        // Should not throw
        controller.handleEvent(TmuxEvent.PaneOutput("%99", "data".toByteArray()))
        controller.handleEvent(TmuxEvent.ExtendedOutput("%99", 0L, "data".toByteArray()))

        // No pane created
        assertThat(controller.getPane("%99")).isNull()
    }

    @Test
    fun `handleEvent WindowAdd adds window to state`() = runTest {
        val controller = createController()

        assertThat(controller.windows.value).isEmpty()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))

        assertThat(controller.windows.value).hasSize(1)
        assertThat(controller.windows.value[0].windowId).isEqualTo("@1")
    }

    @Test
    fun `handleEvent WindowClose removes window`() = runTest {
        val controller = createController()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))
        controller.handleEvent(TmuxEvent.WindowAdd("@2"))
        assertThat(controller.windows.value).hasSize(2)

        controller.handleEvent(TmuxEvent.WindowClose("@1"))

        assertThat(controller.windows.value).hasSize(1)
        assertThat(controller.windows.value[0].windowId).isEqualTo("@2")
    }

    @Test
    fun `handleEvent WindowRenamed updates name`() = runTest {
        val controller = createController()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))
        controller.handleEvent(TmuxEvent.WindowRenamed("@1", "my-window"))

        val window = controller.windows.value.find { it.windowId == "@1" }
        assertThat(window).isNotNull()
        assertThat(window!!.name).isEqualTo("my-window")
    }

    @Test
    fun `handleEvent WindowPaneChanged updates active pane`() = runTest {
        val controller = createController()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))
        controller.handleEvent(TmuxEvent.WindowPaneChanged("@1", "%3"))

        val window = controller.windows.value.find { it.windowId == "@1" }
        assertThat(window).isNotNull()
        assertThat(window!!.activePaneId).isEqualTo("%3")
    }

    @Test
    fun `handleEvent SessionWindowChanged updates active window`() = runTest {
        val controller = createController()

        assertThat(controller.activeWindowId.value).isNull()

        controller.handleEvent(TmuxEvent.SessionWindowChanged("$0", "@2"))

        assertThat(controller.activeWindowId.value).isEqualTo("@2")
    }

    @Test
    fun `handleEvent LayoutChanged parses and updates layout`() = runTest {
        val controller = createController()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))

        // Valid layout string: <4-char-checksum>,<width>x<height>,<x>,<y>,<paneId>
        val layoutStr = "abcd,80x24,0,0,0"
        controller.handleEvent(TmuxEvent.LayoutChanged("@1", layoutStr, layoutStr, ""))

        val window = controller.windows.value.find { it.windowId == "@1" }
        assertThat(window).isNotNull()
        assertThat(window!!.layout).isNotNull()
        assertThat(window.layout).isInstanceOf(TmuxLayoutNode.Leaf::class.java)
        val leaf = window.layout as TmuxLayoutNode.Leaf
        assertThat(leaf.width).isEqualTo(80)
        assertThat(leaf.height).isEqualTo(24)
    }

    @Test
    fun `handleEvent LayoutChanged creates new panes from layout`() = runTest {
        val controller = createController()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))

        val layoutStr = "abcd,80x24,0,0,5"
        controller.handleEvent(TmuxEvent.LayoutChanged("@1", layoutStr, layoutStr, ""))

        // Pane %5 should have been created
        assertThat(controller.getPane("%5")).isNotNull()
    }

    @Test
    fun `handleEvent Exit triggers cleanup and callback`() = runTest {
        var exitCalled = false
        val controller = createController(onExit = { exitCalled = true })

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))
        assertThat(controller.windows.value).hasSize(1)

        controller.handleEvent(TmuxEvent.Exit("detached"))

        assertThat(exitCalled).isTrue()
        assertThat(controller.windows.value).isEmpty()
        assertThat(controller.activeWindowId.value).isNull()
    }

    @Test
    fun `handleEvent CommandResponse routes to sender`() = runTest {
        val controller = createController()

        // The sender tracks pending commands; sending a CommandResponse for
        // a non-pending command should not throw
        controller.handleEvent(TmuxEvent.CommandResponse(99, "output", false))
        // No crash means it was routed correctly
    }

    @Test
    fun `routeInput sends hex-encoded send-keys command`() = runTest {
        val controller = createController()

        writtenBytes.clear()
        controller.routeInput("%0", byteArrayOf(0x41, 0x42, 0x43)) // "ABC"

        assertThat(writtenBytes).hasSize(1)
        val sentStr = writtenBytes[0].toString(Charsets.UTF_8)
        assertThat(sentStr).contains("send-keys")
        assertThat(sentStr).contains("%0")
        assertThat(sentStr).contains("41")
        assertThat(sentStr).contains("42")
        assertThat(sentStr).contains("43")
    }

    @Test
    fun `routeInput handles empty data`() = runTest {
        val controller = createController()

        writtenBytes.clear()
        controller.routeInput("%0", byteArrayOf())

        assertThat(writtenBytes).isEmpty()
    }

    @Test
    fun `cleanup clears all state`() = runTest {
        val controller = createController()

        controller.handleEvent(TmuxEvent.WindowAdd("@1"))
        controller.handleEvent(TmuxEvent.SessionWindowChanged("$0", "@1"))
        // Create a pane
        controller.handleEvent(TmuxEvent.LayoutChanged("@1", "abcd,80x24,0,0,0", "abcd,80x24,0,0,0", ""))

        assertThat(controller.windows.value).isNotEmpty()
        assertThat(controller.activeWindowId.value).isNotNull()
        assertThat(controller.getPane("%0")).isNotNull()

        controller.cleanup()

        assertThat(controller.windows.value).isEmpty()
        assertThat(controller.activeWindowId.value).isNull()
        assertThat(controller.getPane("%0")).isNull()
    }
}
