package org.connectbot.tmux

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class TmuxCommandSenderTest {

    @Test
    fun `sendCommand formats command with newline`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.sendCommand("list-windows")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent).hasSize(1)
        assertThat(sent[0]).isEqualTo("list-windows\n")
    }

    @Test
    fun `sendCommand returns response when completed`() = runTest {
        val sender = TmuxCommandSender { }

        val job = launch {
            val response = sender.sendCommand("list-windows")
            assertThat(response.output).isEqualTo("@0 bash\n@1 vim")
            assertThat(response.isError).isFalse()
        }

        testScheduler.advanceUntilIdle()

        // Simulate response from parser
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "@0 bash\n@1 vim", false))
        testScheduler.advanceUntilIdle()

        job.join()
    }

    @Test
    fun `sendCommand returns error response`() = runTest {
        val sender = TmuxCommandSender { }

        val job = launch {
            val response = sender.sendCommand("select-window -t @99")
            assertThat(response.isError).isTrue()
            assertThat(response.output).isEqualTo("no such window: @99")
        }

        testScheduler.advanceUntilIdle()
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "no such window: @99", true))
        testScheduler.advanceUntilIdle()

        job.join()
    }

    @Test
    fun `FIFO ordering - responses correlate in order`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        // Use Dispatchers.Unconfined to avoid test scheduler timing issues
        val result1 = CompletableDeferred<String>()
        val result2 = CompletableDeferred<String>()

        launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            result1.complete(sender.sendCommand("cmd1").output)
        }
        launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            result2.complete(sender.sendCommand("cmd2").output)
        }

        // Both commands should be sent
        assertThat(sent).hasSize(2)
        assertThat(sent[0]).isEqualTo("cmd1\n")
        assertThat(sent[1]).isEqualTo("cmd2\n")

        // First response goes to cmd1 (FIFO)
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "response-A", false))
        assertThat(result1.await()).isEqualTo("response-A")

        // Second response goes to cmd2
        sender.onCommandResponse(TmuxEvent.CommandResponse(1, "response-B", false))
        assertThat(result2.await()).isEqualTo("response-B")
    }

    @Test
    fun `sendCommandFire does not track response`() {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        sender.sendCommandFire("kill-server")

        assertThat(sent).hasSize(1)
        assertThat(sent[0]).isEqualTo("kill-server\n")

        // Sending a response should not crash (no pending deferred)
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `sendKeysLiteral escapes single quotes`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.sendKeysLiteral("%0", "it's a test")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("send-keys -t '%0' -l 'it'\\''s a test'\n")

        // Complete the pending response to avoid hanging
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `sendKeysHex formats hex codes`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.sendKeysHex("%0", "1b 5b 41")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("send-keys -t '%0' -H 1b 5b 41\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `resizeClient formats dimensions`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.resizeClient(120, 40)
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("refresh-client -C 120x40\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `enableFlowControl formats pause-after`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.enableFlowControl(2)
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("refresh-client -f pause-after=2\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `newWindow with name`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.newWindow("my-window")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("new-window -n 'my-window'\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `newWindow without name`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.newWindow()
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("new-window\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `onCommandResponse with no pending does not crash`() {
        val sender = TmuxCommandSender { }
        // Should be silently ignored
        sender.onCommandResponse(TmuxEvent.CommandResponse(99, "orphan", false))
    }

    @Test
    fun `renameWindow escapes special characters`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.renameWindow("@0", "it's complex")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("rename-window -t '@0' 'it'\\''s complex'\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "", false))
    }

    @Test
    fun `capturePane command format`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        launch {
            sender.capturePane("%0")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("capture-pane -p -e -t '%0'\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "captured content", false))
    }

    @Test
    fun `getVersion command format`() = runTest {
        val sent = mutableListOf<String>()
        val sender = TmuxCommandSender { bytes -> sent.add(String(bytes, Charsets.UTF_8)) }

        val job = launch {
            val version = sender.getVersion()
            assertThat(version).isEqualTo("3.3a")
        }
        testScheduler.advanceUntilIdle()

        assertThat(sent[0]).isEqualTo("display-message -p '#{version}'\n")
        sender.onCommandResponse(TmuxEvent.CommandResponse(0, "3.3a\n", false))
        testScheduler.advanceUntilIdle()

        job.join()
    }
}
