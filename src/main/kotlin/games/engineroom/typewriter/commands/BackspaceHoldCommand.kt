package games.engineroom.typewriter.commands

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import games.engineroom.typewriter.KeyboardSoundService
import kotlin.random.Random

/**
 * Imitates a press-and-hold of Backspace: characters disappear one at a time at the typewriter's
 * pace, but only **one** click sound is played (at the start) — modelling a single key being held
 * rather than tapped repeatedly. For "key tapped N times" semantics (one sound + one IDE smart-
 * backspace fire per press), use [BackspaceCommand] instead.
 *
 * Each per-character delete goes through `Document.deleteString(end - 1, end)` directly, not the
 * IDE's backspace action — a held key just removes raw characters; we don't want language smart-
 * backspace firing N times mid-burst.
 *
 * Stops early if the caret hits offset 0 before [count] chars have been removed.
 */
class BackspaceHoldCommand(
    private val count: Int,
    private val stepDelay: Long,
    private val jitter: Int,
    private val editor: Editor,
    private val pauseAfter: Int,
) : Command {
    override fun run() {
        if (count <= 0) {
            Thread.sleep(pauseAfter.toLong())
            return
        }
        KeyboardSoundService.get().playKey()
        var remaining = count
        while (remaining-- > 0) {
            if (!deleteOneCharBackward()) break
            Thread.sleep(stepPause())
        }
        Thread.sleep(pauseAfter.toLong())
    }

    private fun deleteOneCharBackward(): Boolean {
        var deleted = false
        WriteCommandAction.runWriteCommandAction(editor.project) {
            val caret = editor.caretModel.primaryCaret
            val end = caret.offset
            if (end <= 0) return@runWriteCommandAction
            val start = end - 1
            editor.document.deleteString(start, end)
            caret.moveToOffset(start)
            editor.scrollingModel.scrollToCaret(ScrollType.RELATIVE)
            deleted = true
        }
        return deleted
    }

    private fun stepPause(): Long {
        if (jitter <= 0) return stepDelay.coerceAtLeast(0L)
        val v = stepDelay + Random.nextInt(-jitter, jitter + 1)
        return v.coerceAtLeast(0L)
    }
}
