package org.connectbot.tmux

import androidx.compose.ui.graphics.Color
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TmuxPaneTest {

    /**
     * Recording stub for [PaneTerminalOps].
     *
     * [TmuxPane] uses the non-sealed [PaneTerminalOps] interface internally, which allows
     * tests to inject a simple recording implementation. This avoids native JNI calls that
     * the real [org.connectbot.terminal.TerminalEmulator] requires (it is a sealed interface
     * with a JNI-backed implementation that is not available in JVM unit tests).
     */
    private inner class RecordingTerminalOps : PaneTerminalOps {
        val writeInputCalls = mutableListOf<Triple<ByteArray, Int, Int>>()
        val resizeCalls = mutableListOf<Pair<Int, Int>>()

        override fun writeInput(data: ByteArray) {
            writeInputCalls.add(Triple(data, 0, data.size))
        }

        override fun resize(cols: Int, rows: Int) {
            resizeCalls.add(Pair(cols, rows))
        }
    }

    @Test
    fun `paneId is accessible`() {
        val pane = createTestPane()
        assertThat(pane.paneId).isEqualTo("%0")
    }

    @Test
    fun `terminalEmulator is null when custom ops factory is used`() {
        val pane = createTestPane()
        // When a custom terminalOpsFactory is injected the emulator is not created
        assertThat(pane.terminalEmulator).isNull()
    }

    @Test
    fun `terminalEmulator is non-null when default factory is used`() {
        // Use the default production factory (no custom terminalOpsFactory).
        // TerminalEmulatorFactory.create() itself works under Robolectric; only calling
        // writeInput/resize on the resulting emulator fails due to native JNI.
        val pane = TmuxPane(
            paneId = "%0",
            initialCols = 80,
            initialRows = 24,
            defaultFgColor = Color.White,
            defaultBgColor = Color.Black,
            onKeyboardInput = { }
        )
        assertThat(pane.terminalEmulator).isNotNull()
    }

    @Test
    fun `writeOutput passes data to terminal ops`() {
        val ops = RecordingTerminalOps()
        val pane = createTestPane(ops = ops)
        val data = "hello".toByteArray()

        pane.writeOutput(data)

        assertThat(ops.writeInputCalls).hasSize(1)
        assertThat(ops.writeInputCalls[0].first).isEqualTo(data)
        assertThat(ops.writeInputCalls[0].second).isEqualTo(0)
        assertThat(ops.writeInputCalls[0].third).isEqualTo(data.size)
    }

    @Test
    fun `writeOutput multiple calls all reach terminal ops`() {
        val ops = RecordingTerminalOps()
        val pane = createTestPane(ops = ops)

        pane.writeOutput("hello".toByteArray())
        pane.writeOutput("world\r\n".toByteArray())

        assertThat(ops.writeInputCalls).hasSize(2)
    }

    @Test
    fun `keyboard input factory receives forwarder`() {
        var forwarderReceived = false
        var capturedInput: ByteArray? = null
        val pane = TmuxPane(
            paneId = "%1",
            initialCols = 80,
            initialRows = 24,
            defaultFgColor = Color.White,
            defaultBgColor = Color.Black,
            onKeyboardInput = { data -> capturedInput = data },
            terminalOpsFactory = { onKeyInput ->
                forwarderReceived = true
                RecordingTerminalOps()
            }
        )
        assertThat(forwarderReceived).isTrue()
        assertThat(pane.paneId).isEqualTo("%1")
    }

    @Test
    fun `resize delegates to terminal ops`() {
        val ops = RecordingTerminalOps()
        val pane = createTestPane(ops = ops)

        pane.resize(120, 40)

        assertThat(ops.resizeCalls).containsExactly(Pair(120, 40))
    }

    @Test
    fun `writeOutput handles empty data`() {
        val ops = RecordingTerminalOps()
        val pane = createTestPane(ops = ops)

        pane.writeOutput(ByteArray(0))

        assertThat(ops.writeInputCalls).hasSize(1)
        assertThat(ops.writeInputCalls[0].third).isEqualTo(0)
    }

    @Test
    fun `writeOutput handles escape sequences`() {
        val ops = RecordingTerminalOps()
        val pane = createTestPane(ops = ops)
        // ESC [ 31 m = set red foreground
        val data = "\u001b[31mred text\u001b[0m".toByteArray()

        pane.writeOutput(data)

        assertThat(ops.writeInputCalls).hasSize(1)
        assertThat(ops.writeInputCalls[0].first).isEqualTo(data)
    }

    @Test
    fun `multiple panes can coexist`() {
        val ops0 = RecordingTerminalOps()
        val ops1 = RecordingTerminalOps()
        val ops2 = RecordingTerminalOps()

        val pane0 = createTestPane("%0", ops = ops0)
        val pane1 = createTestPane("%1", ops = ops1)
        val pane2 = createTestPane("%2", ops = ops2)

        pane0.writeOutput("pane 0".toByteArray())
        pane1.writeOutput("pane 1".toByteArray())
        pane2.writeOutput("pane 2".toByteArray())

        assertThat(pane0.paneId).isEqualTo("%0")
        assertThat(pane1.paneId).isEqualTo("%1")
        assertThat(pane2.paneId).isEqualTo("%2")

        assertThat(ops0.writeInputCalls).hasSize(1)
        assertThat(ops1.writeInputCalls).hasSize(1)
        assertThat(ops2.writeInputCalls).hasSize(1)
    }

    private fun createTestPane(
        id: String = "%0",
        ops: PaneTerminalOps = RecordingTerminalOps()
    ) = TmuxPane(
        paneId = id,
        initialCols = 80,
        initialRows = 24,
        defaultFgColor = Color.White,
        defaultBgColor = Color.Black,
        onKeyboardInput = { },
        terminalOpsFactory = { _ -> ops }
    )
}
