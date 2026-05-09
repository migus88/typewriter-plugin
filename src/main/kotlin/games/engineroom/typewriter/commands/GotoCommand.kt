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
 * Stepping rule (recomputed every tick from the live caret offset, so column drift across short
 * lines converges):
 * 1. `currentLine < targetLine` → press Down.
 * 2. `currentLine > targetLine` → press Up.
 * 3. Same line, `currentCol < targetCol` → **Alt+Right** (word-skip) when the next word boundary
 *    on the line lands at-or-before the target; otherwise plain Right.
 * 4. Same line, `currentCol > targetCol` → **Alt+Left** (word-skip backward) when the previous
 *    word boundary lands at-or-after the target; otherwise plain Left.
 *
 * Word-skip jumps go through `Caret.moveToOffset` rather than the IDE's word-navigation actions
 * so the landing point is fully predictable — we don't have to detect overshoot from the IDE's
 * own boundary heuristics. The boundary predictor below approximates the common alt+arrow
 * behaviour: skip a run of non-word chars, then a run of word chars (or vice versa). It's bounded
 * to the current logical line so word-skip can't accidentally cross into another line.
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
        val targetLineStart = document.getLineStartOffset(targetLine)
        val targetLineEnd = document.getLineEndOffset(targetLine)

        // Safety cap so a logic bug can't burn unbounded typing time. Word-skip pushes the
        // realistic step count well below this; the bound is just a backstop.
        var budget = (document.textLength + 64).coerceAtLeast(64)
        while (budget-- > 0) {
            val currentOffset = readCaretOffset()
            if (currentOffset == targetOffset) break

            val currentLine = document.getLineNumber(currentOffset)
            val currentCol = currentOffset - document.getLineStartOffset(currentLine)

            val action: StepAction = when {
                currentLine < targetLine -> StepAction.SingleDown
                currentLine > targetLine -> StepAction.SingleUp
                currentCol < targetCol -> {
                    val nextStop = nextWordStopForward(text, currentOffset, targetLineEnd)
                    if (nextStop > currentOffset && nextStop <= targetOffset) StepAction.JumpTo(nextStop)
                    else StepAction.SingleRight
                }
                else -> {
                    val prevStop = prevWordStopBackward(text, currentOffset, targetLineStart)
                    if (prevStop < currentOffset && prevStop >= targetOffset) StepAction.JumpTo(prevStop)
                    else StepAction.SingleLeft
                }
            }

            KeyboardSoundService.get().playKey()
            applyStep(action)
            Thread.sleep(stepPause())
        }
        Thread.sleep(pauseAfter.toLong())
    }

    private sealed class StepAction {
        object SingleUp : StepAction()
        object SingleDown : StepAction()
        object SingleLeft : StepAction()
        object SingleRight : StepAction()
        data class JumpTo(val offset: Int) : StepAction()
    }

    private fun applyStep(action: StepAction) {
        ApplicationManager.getApplication().invokeAndWait {
            val caret = editor.caretModel.primaryCaret
            when (action) {
                StepAction.SingleUp -> caret.moveCaretRelatively(0, -1, false, false)
                StepAction.SingleDown -> caret.moveCaretRelatively(0, 1, false, false)
                StepAction.SingleLeft -> caret.moveCaretRelatively(-1, 0, false, false)
                StepAction.SingleRight -> caret.moveCaretRelatively(1, 0, false, false)
                is StepAction.JumpTo -> caret.moveToOffset(action.offset)
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

    private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

    /**
     * Approximate alt+Right landing point on the current line: skip a run of non-word characters,
     * then a run of word characters. Capped at [lineEnd] so word-skip can't cross into the next
     * line.
     */
    private fun nextWordStopForward(text: CharSequence, from: Int, lineEnd: Int): Int {
        var i = from
        while (i < lineEnd && !isWordChar(text[i])) i++
        while (i < lineEnd && isWordChar(text[i])) i++
        return i
    }

    /**
     * Approximate alt+Left landing point on the current line: skip a run of non-word characters
     * to the left, then a run of word characters. Capped at [lineStart].
     */
    private fun prevWordStopBackward(text: CharSequence, from: Int, lineStart: Int): Int {
        var i = from
        while (i > lineStart && !isWordChar(text[i - 1])) i--
        while (i > lineStart && isWordChar(text[i - 1])) i--
        return i
    }
}
