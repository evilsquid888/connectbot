package org.connectbot.tmux

import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test

class TmuxControlModeParserTest {

    private lateinit var parser: TmuxControlModeParser

    @Before
    fun setUp() {
        parser = TmuxControlModeParser()
    }

    @After
    fun tearDown() {
        parser.close()
    }

    // --- Octal Escape Decoder ---

    @Test
    fun `decodeOctalEscapes - plain ASCII passthrough`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("hello world")
        assertThat(String(result)).isEqualTo("hello world")
    }

    @Test
    fun `decodeOctalEscapes - newline escape`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("hello\\012world")
        assertThat(result).isEqualTo("hello\nworld".toByteArray())
    }

    @Test
    fun `decodeOctalEscapes - carriage return`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("hello\\015\\012")
        assertThat(result).isEqualTo("hello\r\n".toByteArray())
    }

    @Test
    fun `decodeOctalEscapes - escape character`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("\\033[31m")
        assertThat(result[0]).isEqualTo(0x1B.toByte())
        assertThat(String(result, 1, result.size - 1)).isEqualTo("[31m")
    }

    @Test
    fun `decodeOctalEscapes - backslash literal`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("path\\134file")
        assertThat(String(result)).isEqualTo("path\\file")
    }

    @Test
    fun `decodeOctalEscapes - multiple escapes`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("\\033[1m\\033[31mred\\033[0m")
        assertThat(result[0]).isEqualTo(0x1B.toByte())
    }

    @Test
    fun `decodeOctalEscapes - empty string`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("")
        assertThat(result).isEmpty()
    }

    @Test
    fun `decodeOctalEscapes - backslash at end of string`() {
        // Trailing backslash without enough chars for octal - treat as literal
        val result = TmuxControlModeParser.decodeOctalEscapes("test\\")
        assertThat(String(result)).isEqualTo("test\\")
    }

    @Test
    fun `decodeOctalEscapes - invalid octal digits`() {
        // \999 is not valid octal (9 > 7), should be treated as literal
        val result = TmuxControlModeParser.decodeOctalEscapes("\\999")
        assertThat(String(result)).isEqualTo("\\999")
    }

    @Test
    fun `decodeOctalEscapes - null byte`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("\\000")
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0]).isEqualTo(0.toByte())
    }

    @Test
    fun `decodeOctalEscapes - max value 377`() {
        val result = TmuxControlModeParser.decodeOctalEscapes("\\377")
        assertThat(result.size).isEqualTo(1)
        assertThat(result[0]).isEqualTo(0xFF.toByte())
    }

    // --- Notification Parsing ---

    @Test
    fun `parse window-add notification`() = runTest {
        parser.feedLine("%window-add @0")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.WindowAdd::class.java)
        assertThat((event as TmuxEvent.WindowAdd).windowId).isEqualTo("@0")
    }

    @Test
    fun `parse window-close notification`() = runTest {
        parser.feedLine("%window-close @1")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.WindowClose::class.java)
        assertThat((event as TmuxEvent.WindowClose).windowId).isEqualTo("@1")
    }

    @Test
    fun `parse window-renamed notification`() = runTest {
        parser.feedLine("%window-renamed @0 my-window")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.WindowRenamed::class.java)
        val renamed = event as TmuxEvent.WindowRenamed
        assertThat(renamed.windowId).isEqualTo("@0")
        assertThat(renamed.name).isEqualTo("my-window")
    }

    @Test
    fun `parse window-renamed with spaces in name`() = runTest {
        parser.feedLine("%window-renamed @0 my cool window")
        val event = parser.events.tryReceive().getOrNull()
        val renamed = event as TmuxEvent.WindowRenamed
        assertThat(renamed.name).isEqualTo("my cool window")
    }

    @Test
    fun `parse session-changed notification`() = runTest {
        parser.feedLine("%session-changed \$0 main")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.SessionChanged::class.java)
        val sc = event as TmuxEvent.SessionChanged
        assertThat(sc.sessionId).isEqualTo("\$0")
        assertThat(sc.name).isEqualTo("main")
    }

    @Test
    fun `parse sessions-changed notification`() = runTest {
        parser.feedLine("%sessions-changed")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isEqualTo(TmuxEvent.SessionsChanged)
    }

    @Test
    fun `parse exit notification`() = runTest {
        parser.feedLine("%exit")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isEqualTo(TmuxEvent.Exit)
    }

    @Test
    fun `parse pause notification`() = runTest {
        parser.feedLine("%pause %0")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.Pause::class.java)
        assertThat((event as TmuxEvent.Pause).paneId).isEqualTo("%0")
    }

    @Test
    fun `parse continue notification`() = runTest {
        parser.feedLine("%continue %0")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.Continue::class.java)
        assertThat((event as TmuxEvent.Continue).paneId).isEqualTo("%0")
    }

    @Test
    fun `parse layout-change notification`() = runTest {
        parser.feedLine("%layout-change @0 4a0a,159x44,0,0{79x44,0,0,0,79x44,80,0,1}")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.LayoutChanged::class.java)
        val lc = event as TmuxEvent.LayoutChanged
        assertThat(lc.windowId).isEqualTo("@0")
        assertThat(lc.layout).isEqualTo("4a0a,159x44,0,0{79x44,0,0,0,79x44,80,0,1}")
    }

    @Test
    fun `parse pane-mode-changed notification`() = runTest {
        parser.feedLine("%pane-mode-changed %0")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.PaneModeChanged::class.java)
    }

    @Test
    fun `parse window-pane-changed notification`() = runTest {
        parser.feedLine("%window-pane-changed @0 %1")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.WindowPaneChanged::class.java)
        val wpc = event as TmuxEvent.WindowPaneChanged
        assertThat(wpc.windowId).isEqualTo("@0")
        assertThat(wpc.paneId).isEqualTo("%1")
    }

    @Test
    fun `parse unlinked-window-add notification`() = runTest {
        parser.feedLine("%unlinked-window-add @2")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.UnlinkedWindowAdd::class.java)
    }

    @Test
    fun `parse subscription-changed notification`() = runTest {
        parser.feedLine("%subscription-changed my-sub some value here")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.SubscriptionChanged::class.java)
        val sc = event as TmuxEvent.SubscriptionChanged
        assertThat(sc.name).isEqualTo("my-sub")
        assertThat(sc.value).isEqualTo("some value here")
    }

    // --- Output Parsing ---

    @Test
    fun `parse output notification with plain text`() = runTest {
        parser.feedLine("%output %0 hello world")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.PaneOutput::class.java)
        val po = event as TmuxEvent.PaneOutput
        assertThat(po.paneId).isEqualTo("%0")
        assertThat(String(po.data)).isEqualTo("hello world")
    }

    @Test
    fun `parse output notification with octal escapes`() = runTest {
        parser.feedLine("%output %0 \\033[31mred\\033[0m")
        val event = parser.events.tryReceive().getOrNull()
        val po = event as TmuxEvent.PaneOutput
        assertThat(po.paneId).isEqualTo("%0")
        assertThat(po.data[0]).isEqualTo(0x1B.toByte()) // ESC
    }

    @Test
    fun `parse output notification with newlines`() = runTest {
        parser.feedLine("%output %0 line1\\015\\012line2")
        val event = parser.events.tryReceive().getOrNull()
        val po = event as TmuxEvent.PaneOutput
        assertThat(String(po.data)).isEqualTo("line1\r\nline2")
    }

    @Test
    fun `parse extended-output notification`() = runTest {
        parser.feedLine("%extended-output %0 150 : hello\\012")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.ExtendedOutput::class.java)
        val eo = event as TmuxEvent.ExtendedOutput
        assertThat(eo.paneId).isEqualTo("%0")
        assertThat(eo.msBehind).isEqualTo(150L)
        assertThat(String(eo.data)).isEqualTo("hello\n")
    }

    // --- Command Response Blocks ---

    @Test
    fun `parse command response block - success`() = runTest {
        parser.feedLine("%begin 1709000000 42 1")
        parser.feedLine("window @0: some-name")
        parser.feedLine("%end 1709000000 42 1")

        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.CommandResponse::class.java)
        val cr = event as TmuxEvent.CommandResponse
        assertThat(cr.commandNum).isEqualTo(42)
        assertThat(cr.output).isEqualTo("window @0: some-name")
        assertThat(cr.isError).isFalse()
    }

    @Test
    fun `parse command response block - error`() = runTest {
        parser.feedLine("%begin 1709000000 43 1")
        parser.feedLine("no such window: @99")
        parser.feedLine("%error 1709000000 43 1")

        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.CommandResponse::class.java)
        val cr = event as TmuxEvent.CommandResponse
        assertThat(cr.commandNum).isEqualTo(43)
        assertThat(cr.isError).isTrue()
    }

    @Test
    fun `parse command response block - empty output`() = runTest {
        parser.feedLine("%begin 1709000000 44 1")
        parser.feedLine("%end 1709000000 44 1")

        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.CommandResponse::class.java)
        val cr = event as TmuxEvent.CommandResponse
        assertThat(cr.output).isEmpty()
    }

    @Test
    fun `parse command response block - multiline output`() = runTest {
        parser.feedLine("%begin 1709000000 45 1")
        parser.feedLine("line 1")
        parser.feedLine("line 2")
        parser.feedLine("line 3")
        parser.feedLine("%end 1709000000 45 1")

        val event = parser.events.tryReceive().getOrNull()
        val cr = event as TmuxEvent.CommandResponse
        assertThat(cr.output).isEqualTo("line 1\nline 2\nline 3")
    }

    @Test
    fun `notifications between begin and end are still delivered`() = runTest {
        // Notifications can arrive during a command response block
        parser.feedLine("%begin 1709000000 46 1")
        parser.feedLine("%output %0 hello")
        parser.feedLine("block body")
        parser.feedLine("%end 1709000000 46 1")

        // The %output notification should be delivered before the block response
        val event1 = parser.events.tryReceive().getOrNull()
        assertThat(event1).isInstanceOf(TmuxEvent.PaneOutput::class.java)

        val event2 = parser.events.tryReceive().getOrNull()
        assertThat(event2).isInstanceOf(TmuxEvent.CommandResponse::class.java)
        val cr = event2 as TmuxEvent.CommandResponse
        assertThat(cr.output).isEqualTo("block body")
    }

    // --- Edge Cases ---

    @Test
    fun `unknown notification is silently ignored`() = runTest {
        parser.feedLine("%unknown-notification something")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isNull()
    }

    @Test
    fun `empty line outside block is ignored`() = runTest {
        parser.feedLine("")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isNull()
    }

    @Test
    fun `output with space as pane data`() = runTest {
        // "%output %0 " → pane %0, data is " " (a single space)
        parser.feedLine("%output %0  ")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.PaneOutput::class.java)
        val po = event as TmuxEvent.PaneOutput
        assertThat(String(po.data)).isEqualTo(" ")
    }

    @Test
    fun `output with single character data`() = runTest {
        parser.feedLine("%output %0 x")
        val event = parser.events.tryReceive().getOrNull()
        assertThat(event).isInstanceOf(TmuxEvent.PaneOutput::class.java)
        val po = event as TmuxEvent.PaneOutput
        assertThat(String(po.data)).isEqualTo("x")
    }
}
