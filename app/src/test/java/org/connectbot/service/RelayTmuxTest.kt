/*
 * ConnectBot: simple, powerful, open-source SSH client for Android
 * Copyright 2025 Kenny Root
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.connectbot.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.connectbot.di.CoroutineDispatchers
import org.connectbot.tmux.TmuxControlModeParser
import org.connectbot.transport.AbsTransport
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for Relay's tmux control mode DCS detection and line-buffering logic.
 *
 * These tests exercise [Relay.feedToLineBuffer] and the DCS scanning behavior
 * without requiring Android context or a live transport. The DCS scanning is
 * tested via the public [Relay.onTmuxControlModeDetected] callback by feeding
 * a mock transport that returns pre-crafted byte sequences.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RelayTmuxTest {

    private lateinit var parser: TmuxControlModeParser

    /** A minimal CoroutineDispatchers that uses the test dispatcher for all contexts. */
    private fun makeDispatchers(testDispatcher: TestDispatcher): CoroutineDispatchers = CoroutineDispatchers(
        default = testDispatcher,
        io = testDispatcher,
        main = testDispatcher
    )

    /**
     * Build a Relay whose transport immediately returns [data] then EOF.
     * The relay is not started; we only use it to call [Relay.feedToLineBuffer]
     * and to inspect [Relay.tmuxParser].
     */
    private fun buildRelay(testDispatcher: TestDispatcher): Relay {
        val mockBridge: TerminalBridge = mock()
        val mockTransport: AbsTransport = mock()
        val dispatchers = makeDispatchers(testDispatcher)
        return Relay(mockBridge, mockTransport, dispatchers, "UTF-8")
    }

    @Before
    fun setUp() {
        parser = TmuxControlModeParser()
    }

    @After
    fun tearDown() {
        parser.close()
    }

    // -----------------------------------------------------------------------
    // feedToLineBuffer tests
    // -----------------------------------------------------------------------

    @Test
    fun `feedToLineBuffer - single complete line is delivered to parser`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        relay.feedToLineBuffer("%sessions-changed\n")

        val event = parser.events.tryReceive()
        assertThat(event.isSuccess).isTrue()
        assertThat(event.getOrNull()).isEqualTo(org.connectbot.tmux.TmuxEvent.SessionsChanged)
    }

    @Test
    fun `feedToLineBuffer - multiple lines delivered in order`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        relay.feedToLineBuffer("%sessions-changed\n%sessions-changed\n")

        val event1 = parser.events.tryReceive()
        val event2 = parser.events.tryReceive()
        assertThat(event1.isSuccess).isTrue()
        assertThat(event2.isSuccess).isTrue()
    }

    @Test
    fun `feedToLineBuffer - CRLF line endings strip carriage return`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        // Feed a line with \r\n — the \r should be stripped, parser sees "%sessions-changed"
        relay.feedToLineBuffer("%sessions-changed\r\n")

        val event = parser.events.tryReceive()
        assertThat(event.isSuccess).isTrue()
        assertThat(event.getOrNull()).isEqualTo(org.connectbot.tmux.TmuxEvent.SessionsChanged)
    }

    @Test
    fun `feedToLineBuffer - partial line accumulates across calls`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        // Feed in two halves without a newline in the first call
        relay.feedToLineBuffer("%sessions-")
        // No event yet — line is incomplete
        assertThat(parser.events.tryReceive().isFailure).isTrue()

        relay.feedToLineBuffer("changed\n")
        // Now the complete line should be delivered
        val event = parser.events.tryReceive()
        assertThat(event.isSuccess).isTrue()
        assertThat(event.getOrNull()).isEqualTo(org.connectbot.tmux.TmuxEvent.SessionsChanged)
    }

    @Test
    fun `feedToLineBuffer - trailing partial line without newline is not flushed`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        relay.feedToLineBuffer("incomplete-line-no-newline")

        // Nothing emitted: the line buffer holds the partial content
        assertThat(parser.events.tryReceive().isFailure).isTrue()
    }

    @Test
    fun `feedToLineBuffer - empty string produces no events`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        relay.feedToLineBuffer("")

        assertThat(parser.events.tryReceive().isFailure).isTrue()
    }

    @Test
    fun `feedToLineBuffer - line with only newline produces empty line to parser`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        // Use a real parser: an empty line fed through does nothing observable
        // since TmuxControlModeParser ignores lines that don't match any known prefix.
        // What we can verify is that no exception is thrown and no spurious event is emitted.
        relay.tmuxParser = parser

        relay.feedToLineBuffer("\n")

        // Empty line doesn't match any notification, so no event
        assertThat(parser.events.tryReceive().isFailure).isTrue()
    }

    @Test
    fun `feedToLineBuffer - multiple partial feeds then complete line`() = runTest {
        val relay = buildRelay(StandardTestDispatcher(testScheduler))
        relay.tmuxParser = parser

        relay.feedToLineBuffer("%session")
        relay.feedToLineBuffer("s-chang")
        relay.feedToLineBuffer("ed\n")

        val event = parser.events.tryReceive()
        assertThat(event.isSuccess).isTrue()
        assertThat(event.getOrNull()).isEqualTo(org.connectbot.tmux.TmuxEvent.SessionsChanged)
    }

    @Test
    fun `feedToLineBuffer - no parser set does not crash`() {
        val relay = buildRelay(StandardTestDispatcher())
        relay.tmuxParser = null

        // Should not throw even without a parser
        relay.feedToLineBuffer("%sessions-changed\n")
    }

    // -----------------------------------------------------------------------
    // DCS detection flag test via onTmuxControlModeDetected callback
    // -----------------------------------------------------------------------

    @Test
    fun `onTmuxControlModeDetected - callback invoked when transport sends DCS sequence`() = runTest {
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val dispatchers = makeDispatchers(testDispatcher)
        val mockBridge: TerminalBridge = mock()
        val mockTransport: AbsTransport = mock()

        val dcsSequence = byteArrayOf(
            0x1B,
            0x50,
            0x31,
            0x30,
            0x30,
            0x30,
            0x70 // \033P1000p
        )

        // Configure mock transport: first call returns DCS, second returns EOF.
        var callCount = 0
        org.mockito.kotlin.whenever(
            mockTransport.read(
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any(),
                org.mockito.kotlin.any()
            )
        ).thenAnswer { invocation ->
            val buffer = invocation.getArgument<ByteArray>(0)
            val offset = invocation.getArgument<Int>(1)
            val length = invocation.getArgument<Int>(2)
            when (callCount++) {
                0 -> {
                    val n = minOf(dcsSequence.size, length)
                    System.arraycopy(dcsSequence, 0, buffer, offset, n)
                    n
                }

                else -> -1
            }
        }

        val relay = Relay(mockBridge, mockTransport, dispatchers, "UTF-8")

        var callbackInvoked = false
        relay.onTmuxControlModeDetected = { callbackInvoked = true }
        relay.tmuxParser = parser

        relay.start()
        testScheduler.advanceUntilIdle()

        assertThat(callbackInvoked).isTrue()
    }
}
