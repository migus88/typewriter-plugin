package games.engineroom.typewriter.commands

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import games.engineroom.typewriter.KeyboardSoundService
import kotlin.random.Random

/**
 * Walks the caret toward a string match using arrow-key steps — one keystroke per tick, with the
 * standard typing-pace pause (and a click sound) per step. Not a teleport: the viewer sees the
 * caret crawl line-by-line and column-by-column to its destination.
 *
 * Lookup runs against the destination editor's document at execution time (the script may have
 * already typed text into the editor). Search always starts at offset 0.
 *
 * - With no [anchor], the first occurrence of [target] in the document wins; the caret lands
 *   right after [target].
 * - With an [anchor], we find [anchor] first, then search for [target] starting where [anchor]
 *   ends — useful when a generic [target] string ("private static") would otherwise hit the
 *   wrong instance and you can pick a more specific [anchor] to disambiguate.
 *
 * If either lookup fails, the command silently no-ops (after the post-pause).
 *
 * Stepping rule (recomputed every tick from the live caret offset, so line-shorter-than-target
 * column drift converges):
 * 1. If `currentLine < targetLine` → press Down.
 * 2. If `currentLine > targetLine` → press Up.
 * 3. Otherwise, if `currentCol < targetCol` → press Right; if `>` → press Left.
 *
 * Step pacing reuses the typewriter's [stepDelay] + [jitter] so navigation feels like the same
 * hand that's typing the script.
 */
class GotoCommand(
    private val target: String,
    private val anchor: String?,
    private val stepDelay: Long,
    private val jitter: Int,
    private val editor: Editor,
    private val pauseAfter: Int,
) : Command {
    override fun run() {
        if (target.isEmpty()) {
            Thread.sleep(pauseAfter.toLong())
            return
        }

        val document = editor.document
        val text = document.charsSequence.toString()
        val anchorEnd = if (anchor.isNullOrEmpty()) {
            0
        } else {
            val idx = text.indexOf(anchor)
            if (idx < 0) {
                Thread.sleep(pauseAfter.toLong())
                return
            }
            idx + anchor.length
        }
        val targetIdx = text.indexOf(target, startIndex = anchorEnd)
        if (targetIdx < 0) {
            Thread.sleep(pauseAfter.toLong())
            return
        }
        val targetOffset = targetIdx + target.length
        val targetLine = document.getLineNumber(targetOffset)
        val targetCol = targetOffset - document.getLineStartOffset(targetLine)

        // Safety cap so a logic bug can't burn an unbounded amount of typing time. The legitimate
        // upper bound is ~document length keystrokes (one step per character in the worst case).
        var budget = (document.textLength + 64).coerceAtLeast(64)
        while (budget-- > 0) {
            val currentOffset = readCaretOffset()
            if (currentOffset == targetOffset) break

            val currentLine = document.getLineNumber(currentOffset)
            val currentCol = currentOffset - document.getLineStartOffset(currentLine)

            val dir = when {
                currentLine < targetLine -> CaretDirection.DOWN
                currentLine > targetLine -> CaretDirection.UP
                currentCol < targetCol -> CaretDirection.RIGHT
                else -> CaretDirection.LEFT
            }

            KeyboardSoundService.get().playKey()
            stepCaret(dir)
            Thread.sleep(stepPause())
        }
        Thread.sleep(pauseAfter.toLong())
    }

    private fun stepCaret(direction: CaretDirection) {
        ApplicationManager.getApplication().invokeAndWait {
            val caret = editor.caretModel.primaryCaret
            when (direction) {
                CaretDirection.UP -> caret.moveCaretRelatively(0, -1, false, false)
                CaretDirection.DOWN -> caret.moveCaretRelatively(0, 1, false, false)
                CaretDirection.LEFT -> caret.moveCaretRelatively(-1, 0, false, false)
                CaretDirection.RIGHT -> caret.moveCaretRelatively(1, 0, false, false)
            }
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
        }
    }

    private fun readCaretOffset(): Int {
        var v = 0
        ApplicationManager.getApplication().invokeAndWait {
            v = editor.caretModel.primaryCaret.offset
        }
        return v
    }

    private fun stepPause(): Long {
        if (jitter <= 0) return stepDelay.coerceAtLeast(0L)
        val v = stepDelay + Random.nextInt(-jitter, jitter + 1)
        return v.coerceAtLeast(0L)
    }
}
