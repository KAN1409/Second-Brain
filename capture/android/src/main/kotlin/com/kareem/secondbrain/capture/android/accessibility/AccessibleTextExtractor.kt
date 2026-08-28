package com.kareem.secondbrain.capture.android.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

internal data class ExtractedScreenText(
    val text: String,
    val visitedNodes: Int,
    val passwordNodesSkipped: Int,
)

internal object AccessibleTextExtractor {
    private const val MAX_NODES = 800
    private const val MAX_CHARS = 60_000

    fun extract(root: AccessibilityNodeInfo?): ExtractedScreenText {
        if (root == null) return ExtractedScreenText("", 0, 0)
        val lines = LinkedHashSet<String>()
        var visited = 0
        var passwordSkipped = 0
        var chars = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty() && visited < MAX_NODES && chars < MAX_CHARS) {
            val node = stack.removeLast()
            visited++
            if (node.isPassword) {
                passwordSkipped++
                continue
            }

            sequenceOf(node.text, node.contentDescription, node.stateDescription)
                .mapNotNull { it?.toString()?.trim() }
                .filter { it.isNotBlank() }
                .forEach { value ->
                    if (chars + value.length <= MAX_CHARS && lines.add(value)) chars += value.length
                }

            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let(stack::add)
            }
        }

        return ExtractedScreenText(
            text = lines.joinToString("\n"),
            visitedNodes = visited,
            passwordNodesSkipped = passwordSkipped,
        )
    }

    @Suppress("unused")
    private fun AccessibilityNodeInfo.boundsString(): String = Rect().also(::getBoundsInScreen).toShortString()
}
