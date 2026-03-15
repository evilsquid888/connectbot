package org.connectbot.tmux

sealed class TmuxLayoutNode {
    abstract val width: Int
    abstract val height: Int
    abstract val x: Int
    abstract val y: Int

    data class Leaf(
        override val width: Int,
        override val height: Int,
        override val x: Int,
        override val y: Int,
        val paneId: Int
    ) : TmuxLayoutNode()

    data class HSplit(
        override val width: Int,
        override val height: Int,
        override val x: Int,
        override val y: Int,
        val children: List<TmuxLayoutNode>
    ) : TmuxLayoutNode()

    data class VSplit(
        override val width: Int,
        override val height: Int,
        override val x: Int,
        override val y: Int,
        val children: List<TmuxLayoutNode>
    ) : TmuxLayoutNode()
}

object TmuxLayoutParser {
    /**
     * Parse a tmux layout string into a tree.
     * @param layoutString the full layout string including checksum prefix
     * @return the root layout node
     * @throws IllegalArgumentException if the layout string is malformed
     */
    fun parse(layoutString: String): TmuxLayoutNode {
        // The checksum prefix is always 4 chars + comma = 5 chars total
        require(layoutString.length > 5) { "Layout string too short: '$layoutString'" }
        val body = layoutString.substring(5)
        require(body.isNotEmpty()) { "Layout string body is empty after checksum" }
        val cursor = Cursor(body)
        val node = parseNode(cursor)
        require(cursor.pos == body.length) {
            "Unexpected trailing characters at position ${cursor.pos}: '${body.substring(cursor.pos)}'"
        }
        return node
    }

    private class Cursor(val input: String) {
        var pos: Int = 0

        fun peek(): Char? = if (pos < input.length) input[pos] else null

        fun consume(): Char {
            require(pos < input.length) { "Unexpected end of input at position $pos" }
            return input[pos++]
        }

        fun consumeChar(expected: Char) {
            val c = consume()
            require(c == expected) { "Expected '$expected' but got '$c' at position ${pos - 1}" }
        }

        fun consumeInt(): Int {
            val start = pos
            while (pos < input.length && input[pos].isDigit()) pos++
            require(pos > start) { "Expected integer at position $start but found '${peek()}'" }
            return input.substring(start, pos).toInt()
        }

        val remaining: Int get() = input.length - pos
    }

    private fun parseNode(cursor: Cursor): TmuxLayoutNode {
        // Parse: <width>x<height>,<x>,<y>
        val width = cursor.consumeInt()
        cursor.consumeChar('x')
        val height = cursor.consumeInt()
        cursor.consumeChar(',')
        val x = cursor.consumeInt()
        cursor.consumeChar(',')
        val y = cursor.consumeInt()

        return when (cursor.peek()) {
            '{' -> {
                cursor.consume() // consume '{'
                val children = parseChildren(cursor, '}')
                TmuxLayoutNode.VSplit(width, height, x, y, children)
            }

            '[' -> {
                cursor.consume() // consume '['
                val children = parseChildren(cursor, ']')
                TmuxLayoutNode.HSplit(width, height, x, y, children)
            }

            ',' -> {
                cursor.consume() // consume ','
                val paneId = cursor.consumeInt()
                TmuxLayoutNode.Leaf(width, height, x, y, paneId)
            }

            null -> throw IllegalArgumentException(
                "Unexpected end of input after dimensions ${width}x$height,$x,$y"
            )

            else -> throw IllegalArgumentException(
                "Unexpected character '${cursor.peek()}' at position ${cursor.pos}"
            )
        }
    }

    private fun parseChildren(cursor: Cursor, closingChar: Char): List<TmuxLayoutNode> {
        val children = mutableListOf<TmuxLayoutNode>()
        children.add(parseNode(cursor))
        while (cursor.peek() == ',') {
            cursor.consume() // consume ','
            children.add(parseNode(cursor))
        }
        require(cursor.peek() == closingChar) {
            "Expected '$closingChar' but got '${cursor.peek()}' at position ${cursor.pos}"
        }
        cursor.consume() // consume closing char
        return children
    }
}
