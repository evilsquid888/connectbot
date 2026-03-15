package org.connectbot.tmux

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.Test

class TmuxLayoutParserTest {

    // --- Single Pane ---

    @Test
    fun `single pane returns leaf node`() {
        val result = TmuxLayoutParser.parse("d3be,180x50,0,0,0")
        assertThat(result).isInstanceOf(TmuxLayoutNode.Leaf::class.java)
        val leaf = result as TmuxLayoutNode.Leaf
        assertThat(leaf.width).isEqualTo(180)
        assertThat(leaf.height).isEqualTo(50)
        assertThat(leaf.x).isEqualTo(0)
        assertThat(leaf.y).isEqualTo(0)
        assertThat(leaf.paneId).isEqualTo(0)
    }

    @Test
    fun `single pane with non-zero position`() {
        val result = TmuxLayoutParser.parse("abcd,80x24,10,5,3")
        assertThat(result).isInstanceOf(TmuxLayoutNode.Leaf::class.java)
        val leaf = result as TmuxLayoutNode.Leaf
        assertThat(leaf.width).isEqualTo(80)
        assertThat(leaf.height).isEqualTo(24)
        assertThat(leaf.x).isEqualTo(10)
        assertThat(leaf.y).isEqualTo(5)
        assertThat(leaf.paneId).isEqualTo(3)
    }

    @Test
    fun `single pane with large pane id`() {
        val result = TmuxLayoutParser.parse("1234,80x24,0,0,42")
        assertThat(result).isInstanceOf(TmuxLayoutNode.Leaf::class.java)
        assertThat((result as TmuxLayoutNode.Leaf).paneId).isEqualTo(42)
    }

    // --- Two Vertical Panes (side by side, VSplit) ---

    @Test
    fun `two vertical panes returns vsplit with two leaf children`() {
        val result = TmuxLayoutParser.parse("5765,180x50,0,0{90x50,0,0,0,89x50,91,0,1}")
        assertThat(result).isInstanceOf(TmuxLayoutNode.VSplit::class.java)
        val vsplit = result as TmuxLayoutNode.VSplit
        assertThat(vsplit.width).isEqualTo(180)
        assertThat(vsplit.height).isEqualTo(50)
        assertThat(vsplit.x).isEqualTo(0)
        assertThat(vsplit.y).isEqualTo(0)
        assertThat(vsplit.children).hasSize(2)

        val left = vsplit.children[0] as TmuxLayoutNode.Leaf
        assertThat(left.width).isEqualTo(90)
        assertThat(left.height).isEqualTo(50)
        assertThat(left.x).isEqualTo(0)
        assertThat(left.y).isEqualTo(0)
        assertThat(left.paneId).isEqualTo(0)

        val right = vsplit.children[1] as TmuxLayoutNode.Leaf
        assertThat(right.width).isEqualTo(89)
        assertThat(right.height).isEqualTo(50)
        assertThat(right.x).isEqualTo(91)
        assertThat(right.y).isEqualTo(0)
        assertThat(right.paneId).isEqualTo(1)
    }

    // --- Two Horizontal Panes (stacked, HSplit) ---

    @Test
    fun `two horizontal panes returns hsplit with two leaf children`() {
        // Note: checksum can contain non-hex chars - just strip first 5 chars
        val result = TmuxLayoutParser.parse("a]34,80x24,0,0[80x12,0,0,0,80x11,0,13,1]")
        assertThat(result).isInstanceOf(TmuxLayoutNode.HSplit::class.java)
        val hsplit = result as TmuxLayoutNode.HSplit
        assertThat(hsplit.width).isEqualTo(80)
        assertThat(hsplit.height).isEqualTo(24)
        assertThat(hsplit.x).isEqualTo(0)
        assertThat(hsplit.y).isEqualTo(0)
        assertThat(hsplit.children).hasSize(2)

        val top = hsplit.children[0] as TmuxLayoutNode.Leaf
        assertThat(top.width).isEqualTo(80)
        assertThat(top.height).isEqualTo(12)
        assertThat(top.x).isEqualTo(0)
        assertThat(top.y).isEqualTo(0)
        assertThat(top.paneId).isEqualTo(0)

        val bottom = hsplit.children[1] as TmuxLayoutNode.Leaf
        assertThat(bottom.width).isEqualTo(80)
        assertThat(bottom.height).isEqualTo(11)
        assertThat(bottom.x).isEqualTo(0)
        assertThat(bottom.y).isEqualTo(13)
        assertThat(bottom.paneId).isEqualTo(1)
    }

    // --- Three Vertical Panes ---

    @Test
    fun `three vertical panes returns vsplit with three leaf children`() {
        // 180x50 split into three: 59x50, 60x50, 60x50
        val result = TmuxLayoutParser.parse("aaaa,180x50,0,0{59x50,0,0,0,60x50,60,0,1,60x50,121,0,2}")
        assertThat(result).isInstanceOf(TmuxLayoutNode.VSplit::class.java)
        val vsplit = result as TmuxLayoutNode.VSplit
        assertThat(vsplit.children).hasSize(3)
        assertThat((vsplit.children[0] as TmuxLayoutNode.Leaf).paneId).isEqualTo(0)
        assertThat((vsplit.children[1] as TmuxLayoutNode.Leaf).paneId).isEqualTo(1)
        assertThat((vsplit.children[2] as TmuxLayoutNode.Leaf).paneId).isEqualTo(2)
    }

    // --- Nested Splits ---

    @Test
    fun `nested splits - vsplit containing leaf and hsplit`() {
        val result = TmuxLayoutParser.parse(
            "5765,180x50,0,0{90x50,0,0,0,89x50,91,0[89x25,91,0,1,89x24,91,26,2]}"
        )
        assertThat(result).isInstanceOf(TmuxLayoutNode.VSplit::class.java)
        val vsplit = result as TmuxLayoutNode.VSplit
        assertThat(vsplit.children).hasSize(2)

        val firstChild = vsplit.children[0]
        assertThat(firstChild).isInstanceOf(TmuxLayoutNode.Leaf::class.java)
        assertThat((firstChild as TmuxLayoutNode.Leaf).paneId).isEqualTo(0)

        val secondChild = vsplit.children[1]
        assertThat(secondChild).isInstanceOf(TmuxLayoutNode.HSplit::class.java)
        val hsplit = secondChild as TmuxLayoutNode.HSplit
        assertThat(hsplit.width).isEqualTo(89)
        assertThat(hsplit.height).isEqualTo(50)
        assertThat(hsplit.x).isEqualTo(91)
        assertThat(hsplit.y).isEqualTo(0)
        assertThat(hsplit.children).hasSize(2)

        val topPane = hsplit.children[0] as TmuxLayoutNode.Leaf
        assertThat(topPane.paneId).isEqualTo(1)
        assertThat(topPane.y).isEqualTo(0)

        val bottomPane = hsplit.children[1] as TmuxLayoutNode.Leaf
        assertThat(bottomPane.paneId).isEqualTo(2)
        assertThat(bottomPane.y).isEqualTo(26)
    }

    @Test
    fun `deeply nested splits`() {
        // VSplit with 2 children, right child is an HSplit with 2 children,
        // bottom of that HSplit is itself a VSplit with 2 children
        val result = TmuxLayoutParser.parse(
            "bbbb,180x50,0,0{90x50,0,0,0,89x50,91,0[89x25,91,0,1,89x24,91,26{44x24,91,26,2,44x24,136,26,3}]}"
        )
        assertThat(result).isInstanceOf(TmuxLayoutNode.VSplit::class.java)
        val root = result as TmuxLayoutNode.VSplit
        assertThat(root.children).hasSize(2)

        val rightChild = root.children[1] as TmuxLayoutNode.HSplit
        assertThat(rightChild.children).hasSize(2)

        val bottomChild = rightChild.children[1] as TmuxLayoutNode.VSplit
        assertThat(bottomChild.children).hasSize(2)
        assertThat((bottomChild.children[0] as TmuxLayoutNode.Leaf).paneId).isEqualTo(2)
        assertThat((bottomChild.children[1] as TmuxLayoutNode.Leaf).paneId).isEqualTo(3)
    }

    // --- Pane IDs > 9 ---

    @Test
    fun `pane id greater than 9`() {
        val result = TmuxLayoutParser.parse("ffff,80x24,0,0,15")
        assertThat(result).isInstanceOf(TmuxLayoutNode.Leaf::class.java)
        assertThat((result as TmuxLayoutNode.Leaf).paneId).isEqualTo(15)
    }

    @Test
    fun `pane id of 100`() {
        val result = TmuxLayoutParser.parse("ffff,80x24,0,0,100")
        assertThat((result as TmuxLayoutNode.Leaf).paneId).isEqualTo(100)
    }

    // --- Error cases ---

    @Test
    fun `empty string throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `only checksum too short throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("d3be") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `checksum only with comma throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("d3be,") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `malformed dimensions throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("d3be,180,50,0,0,0") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `missing pane id throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("d3be,180x50,0,0") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unclosed brace throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("d3be,180x50,0,0{90x50,0,0,0") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unclosed bracket throws IllegalArgumentException`() {
        assertThatThrownBy { TmuxLayoutParser.parse("d3be,180x50,0,0[80x25,0,0,0") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // --- Real tmux output examples ---

    @Test
    fun `real tmux layout change example with two panes`() {
        // From the TmuxControlModeParserTest layout-change notification
        val result = TmuxLayoutParser.parse("4a0a,159x44,0,0{79x44,0,0,0,79x44,80,0,1}")
        assertThat(result).isInstanceOf(TmuxLayoutNode.VSplit::class.java)
        val vsplit = result as TmuxLayoutNode.VSplit
        assertThat(vsplit.width).isEqualTo(159)
        assertThat(vsplit.height).isEqualTo(44)
        assertThat(vsplit.children).hasSize(2)
    }
}
